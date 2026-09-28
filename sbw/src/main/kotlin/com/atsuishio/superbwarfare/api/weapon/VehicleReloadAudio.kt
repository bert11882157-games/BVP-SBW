package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.SoundInfo
import com.atsuishio.superbwarfare.data.gun.value.ReloadState
import com.atsuishio.superbwarfare.diagnostics.VehicleWeaponAudioDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.VehicleReloadSoundMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.network.PacketDistributor
import java.util.LinkedHashMap
import java.util.UUID

/** Server-owned reload presentation. GunEventHandler supplies lifecycle transitions only. */
@Mod.EventBusSubscriber
object VehicleReloadAudio {
    /**
     * Vehicle reload audio is emitted from one server countdown seam. GunData for every vehicle
     * weapon uses the generic vehicle_gun stack, so the typed map identity and persistent reload
     * revision are part of the key; a stack UUID/registry id would alias coax/HMG/main guns.
     */
    private data class VehicleReloadSoundKey(
        val level: Level,
        val vehicleUuid: UUID,
        val weaponIdentity: String,
        val reloadRevision: Int,
    )

    internal class VehicleReloadSoundCycle(
        var lastSeenGameTime: Long,
        val playbackId: UUID = UUID.randomUUID(),
        var emittedSound: SoundEvent? = null,
        val listenerUuids: LinkedHashSet<UUID> = linkedSetOf(),
        var reloadDurationTicks: Int = 0,
        var clipDurationTicks: Int = 0,
        var lastRemainingTicks: Int = Int.MAX_VALUE,
        var localOnly: Boolean = false,
        var stopSent: Boolean = false,
    ) {
        var emitted = false
            private set
        var retired = false
            private set

        fun claim(): Boolean {
            if (emitted || retired) return false
            emitted = true
            return true
        }

        fun retire() { retired = true }

        fun observe(remaining: Int, reloading: Boolean, gameTime: Long): String? {
            if (retired) return null
            val reason = when {
                !reloading || remaining <= 0 -> "reload_cancelled_or_reset"
                lastRemainingTicks != Int.MAX_VALUE &&
                    (remaining > lastRemainingTicks || remaining < lastRemainingTicks - 1) -> "reload_timer_discontinuity"
                else -> null
            }
            if (reason != null) retire()
            else {
                lastRemainingTicks = remaining
                lastSeenGameTime = gameTime
            }
            return reason
        }
    }

    private enum class VehicleReloadSoundClaim {
        CLAIMED,
        DUPLICATE,
        INVALID_KEY,
        NO_ACTIVE_CYCLE,
    }

    /**
     * Keep one presentation claim for the lifetime of a reload cycle rather than only for one
     * server tick.  GunData is copied while a vehicle ticks, and a delayed/repeated callback must
     * never restart the same clip.  This table is presentation-only and is bounded/expired.
     */
    private const val VEHICLE_RELOAD_SOUND_TTL_TICKS = 512L
    private const val MAX_VEHICLE_RELOAD_SOUND_KEYS = 512
    private val vehicleReloadSoundCycles =
        LinkedHashMap<VehicleReloadSoundKey, VehicleReloadSoundCycle>(64, 0.75f, true)
    private var lastPruneTick = Long.MIN_VALUE

    private fun vehicleReloadSoundKey(vehicle: VehicleEntity, data: GunData): VehicleReloadSoundKey? {
        val weaponIdentity = data.vehicleWeaponIdentity?.takeIf { it.isNotBlank() } ?: return null
        val reloadRevision = data.reload.soundCycleRevision()
        // Revision zero means no authoritative reload start has occurred in this GunData. Do not
        // synthesize a recovered cycle from a delayed state receipt.
        if (reloadRevision <= 0) return null
        return VehicleReloadSoundKey(
            vehicle.level(),
            vehicle.getUUID(),
            weaponIdentity,
            reloadRevision,
        )
    }

    private fun pruneVehicleReloadSoundCycles(gameTime: Long) {
        if (lastPruneTick == gameTime && vehicleReloadSoundCycles.size <= MAX_VEHICLE_RELOAD_SOUND_KEYS) return
        lastPruneTick = gameTime
        val stale = vehicleReloadSoundCycles.entries.iterator()
        while (stale.hasNext()) {
            val entry = stale.next()
            if (entry.key.level.gameTime - entry.value.lastSeenGameTime > VEHICLE_RELOAD_SOUND_TTL_TICKS) {
                stopVehicleReloadSoundCycle(entry.key, entry.value, "cycle_ttl_expired")
                stale.remove()
            }
        }
        while (vehicleReloadSoundCycles.size > MAX_VEHICLE_RELOAD_SOUND_KEYS) {
            val oldest = vehicleReloadSoundCycles.entries.iterator()
            if (!oldest.hasNext()) break
            val entry = oldest.next()
            stopVehicleReloadSoundCycle(entry.key, entry.value, "cycle_capacity_evicted")
            oldest.remove()
        }
    }

    fun begin(
        vehicle: VehicleEntity,
        data: GunData,
        gameTime: Long,
    ) {
        if (vehicle.level().isClientSide || !owns(vehicle, data)) return
        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        // A new revision replaces any unfinished presentation for this exact weapon. The old
        // event/listener set is retained in the old cycle, so closing it cannot accidentally stop
        // whichever weapon is selected now.
        val obsolete = vehicleReloadSoundCycles.entries.iterator()
        while (obsolete.hasNext()) {
            val entry = obsolete.next()
            if (entry.key.level === key.level &&
                entry.key.vehicleUuid == key.vehicleUuid &&
                entry.key.weaponIdentity == key.weaponIdentity &&
                entry.key != key
            ) {
                stopVehicleReloadSoundCycle(entry.key, entry.value, "reload_revision_replaced")
                obsolete.remove()
            }
        }
        // A duplicate start/state receipt for the same revision must not clear the emitted bit.
        // A new reload gets a new revision and therefore a distinct key.
        vehicleReloadSoundCycles.getOrPut(key) {
            VehicleReloadSoundCycle(
                lastSeenGameTime = gameTime,
                lastRemainingTicks = data.reload.time(),
            )
        }.apply {
            lastSeenGameTime = gameTime
        }
        pruneVehicleReloadSoundCycles(gameTime)
    }

    fun finish(vehicle: VehicleEntity, data: GunData) {
        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        val cycle = vehicleReloadSoundCycles[key] ?: return
        cycle.retire()
        stopVehicleReloadSoundCycle(key, cycle, "authoritative_reload_complete")
    }

    private fun stopVehicleReloadSoundCycle(
        key: VehicleReloadSoundKey,
        cycle: VehicleReloadSoundCycle,
        reason: String,
    ) {
        if (!cycle.emitted || cycle.stopSent) return
        val sound = cycle.emittedSound ?: return
        cycle.stopSent = true
        val serverLevel = key.level as? ServerLevel ?: return
        val listeners = cycle.listenerUuids.toList()
        cycle.listenerUuids.clear()
        for (listenerUuid in listeners) {
            val listener = serverLevel.server.playerList.getPlayer(listenerUuid) ?: continue
            sendStop(listener, key, cycle, sound)
        }

        val vehicle = serverLevel.getEntity(key.vehicleUuid) as? VehicleEntity ?: return
        val data = vehicle.getGunData(key.weaponIdentity)
        VehicleWeaponAudioDiagnostics.recordServer(
            vehicle,
            vehicle.controllingPassenger as? LivingEntity,
            data,
            "TYPED_VEHICLE_RELOAD_STOP",
            VehicleWeaponAudioDiagnostics.Disposition.AUTHORITATIVE_STOP,
            sound,
            "reason=$reason listeners=${listeners.size} reload_ticks=${cycle.reloadDurationTicks} " +
                "clip_ticks=${cycle.clipDurationTicks}",
        )
    }

    private fun sendStop(
        listener: ServerPlayer, key: VehicleReloadSoundKey,
        cycle: VehicleReloadSoundCycle, sound: SoundEvent,
    ) = sendPacketTo(PacketDistributor.PLAYER.with { listener }, VehicleReloadSoundMessage(
        cycle.playbackId, key.level.dimension().location(), -1, key.vehicleUuid,
        key.weaponIdentity, key.reloadRevision, sound.location, 0, cycle.localOnly, true,
    ))

    @SubscribeEvent
    fun unload(event: LevelEvent.Unload) {
        val iterator = vehicleReloadSoundCycles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.level !== event.level) continue
            stopVehicleReloadSoundCycle(entry.key, entry.value, "level_unloaded")
            iterator.remove()
        }
    }

    /** Stops and retires the exact retained reload event for one vehicle weapon. */
    @JvmStatic
    @JvmOverloads
    fun cancel(
        vehicle: VehicleEntity,
        weaponIdentity: String? = null,
        reason: String = "vehicle_context_cancelled",
    ) {
        if (vehicle.level().isClientSide) return
        val iterator = vehicleReloadSoundCycles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.level !== vehicle.level() || entry.key.vehicleUuid != vehicle.uuid) continue
            if (weaponIdentity != null && entry.key.weaponIdentity != weaponIdentity) continue
            entry.value.retire()
            stopVehicleReloadSoundCycle(entry.key, entry.value, reason)
        }
    }

    /** Stops only the departing listener; other crew keep hearing the same in-progress reload. */
    @JvmStatic
    fun detachListener(
        vehicle: VehicleEntity,
        listenerUuid: UUID,
        reason: String = "listener_detached",
    ) {
        if (vehicle.level().isClientSide) return
        val serverLevel = vehicle.level() as? ServerLevel ?: return
        for ((key, cycle) in vehicleReloadSoundCycles) {
            if (key.level !== vehicle.level() || key.vehicleUuid != vehicle.uuid) continue
            // A dismounted observer still hears world audio; only a local cab channel detaches.
            if (!cycle.localOnly) continue
            if (!cycle.emitted || cycle.stopSent || !cycle.listenerUuids.remove(listenerUuid)) continue
            val sound = cycle.emittedSound ?: continue
            serverLevel.server.playerList.getPlayer(listenerUuid)?.let {
                sendStop(it, key, cycle, sound)
            }
            VehicleWeaponAudioDiagnostics.recordServer(
                vehicle,
                vehicle.controllingPassenger as? LivingEntity,
                vehicle.getGunData(key.weaponIdentity),
                "TYPED_VEHICLE_RELOAD_STOP",
                VehicleWeaponAudioDiagnostics.Disposition.AUTHORITATIVE_STOP,
                sound,
                "reason=$reason listener=$listenerUuid",
            )
        }
    }

    fun reconcile(
        vehicle: VehicleEntity,
        data: GunData,
        gameTime: Long,
    ) {
        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        val cycle = vehicleReloadSoundCycles[key] ?: return
        if (cycle.retired) return
        cycle.observe(data.reload.time(), data.reloading(), gameTime)?.let {
            stopVehicleReloadSoundCycle(key, cycle, it)
            return
        }
        pruneVehicleReloadSoundCycles(gameTime)
    }

    private fun retainVehicleReloadSoundEmission(
        vehicle: VehicleEntity,
        data: GunData,
        sound: SoundEvent,
        listeners: Collection<ServerPlayer>,
        reloadDurationTicks: Int,
        clipDurationTicks: Int,
        localOnly: Boolean,
    ) {
        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        val cycle = vehicleReloadSoundCycles[key] ?: return
        cycle.emittedSound = sound
        cycle.listenerUuids.clear()
        cycle.listenerUuids.addAll(listeners.map { it.uuid })
        cycle.reloadDurationTicks = reloadDurationTicks
        cycle.clipDurationTicks = clipDurationTicks
        cycle.lastRemainingTicks = data.reload.time()
        cycle.localOnly = localOnly
    }

    private fun claimVehicleReloadSound(
        vehicle: VehicleEntity,
        data: GunData,
        gameTime: Long,
    ): VehicleReloadSoundClaim {
        val key = vehicleReloadSoundKey(vehicle, data) ?: return VehicleReloadSoundClaim.INVALID_KEY
        pruneVehicleReloadSoundCycles(gameTime)
        // Claims are valid only for a cycle opened by the authoritative reload-start transition.
        // Never create a late/recovered cycle from a progress callback.
        val cycle = vehicleReloadSoundCycles[key] ?: return VehicleReloadSoundClaim.NO_ACTIVE_CYCLE
        if (cycle.retired) return VehicleReloadSoundClaim.NO_ACTIVE_CYCLE
        cycle.lastSeenGameTime = gameTime
        if (cycle.emitted) return VehicleReloadSoundClaim.DUPLICATE
        if (!cycle.claim()) return VehicleReloadSoundClaim.NO_ACTIVE_CYCLE
        return VehicleReloadSoundClaim.CLAIMED
    }

    private fun isConfiguredVehicleReloadSound(sound: SoundEvent): Boolean {
        return sound.location != SoundEvents.EMPTY.location
    }

    fun tick(vehicle: VehicleEntity, data: GunData) {
        if (vehicle.level().isClientSide || !data.reloading()) return
        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        val cycle = vehicleReloadSoundCycles[key] ?: return
        if (cycle.emitted || cycle.retired) return
        val soundInfo = data.get(GunProp.SOUND_INFO)
        val reloadDuration = data.reload.total().takeIf { it > 0 }
            ?: if (data.reload.state() == ReloadState.NORMAL_RELOADING) {
                data.get(GunProp.NORMAL_RELOAD_TIME)
            } else data.get(GunProp.EMPTY_RELOAD_TIME)
        val countdown = VehicleReloadSoundTiming.countdownFor(
            reloadDuration, soundInfo.vehicleReloadClipDurationTicks,
            soundInfo.vehicleReloadSoundTime, if (data.item.isOpenBolt(data)) 1 else 2,
        )
        // Includes shortened/credited cycles. Never recover or replay a cycle already retired.
        if (data.reload.time() in 3..countdown) emit(vehicle, data, soundInfo, reloadDuration)
    }

    private fun hasConfiguredVehicleReloadSound(soundInfo: SoundInfo): Boolean {
        return isConfiguredVehicleReloadSound(soundInfo.vehicleReload) ||
            isConfiguredVehicleReloadSound(soundInfo.vehicleReload3p)
    }

    /** A typed vehicle reload owns every phase of its audio, including iterative reload helpers. */
    fun owns(shooter: Entity?, data: GunData): Boolean {
        val hasVehicleContext = shooter is VehicleEntity || shooter?.vehicle is VehicleEntity
        return hasVehicleContext &&
            !data.vehicleWeaponIdentity.isNullOrBlank() &&
            hasConfiguredVehicleReloadSound(data.get(GunProp.SOUND_INFO))
    }

    fun recordFallbackSuppressed(
        shooter: Entity?,
        data: GunData,
        channel: String,
        sound: SoundEvent?,
    ) {
        val vehicle = (shooter as? VehicleEntity) ?: (shooter?.vehicle as? VehicleEntity) ?: return
        val controller = (shooter as? LivingEntity) ?: (vehicle.controllingPassenger as? LivingEntity)
        VehicleWeaponAudioDiagnostics.recordServer(
            vehicle,
            controller,
            data,
            channel,
            VehicleWeaponAudioDiagnostics.Disposition.FALLBACK_SUPPRESSED,
            sound,
            "typed_vehicle_reload_owner=true",
        )
    }

    fun recordNativePlayed(
        shooter: ServerPlayer,
        data: GunData,
        channel: String,
        sound: SoundEvent,
    ) {
        val vehicle = shooter.vehicle as? VehicleEntity ?: return
        VehicleWeaponAudioDiagnostics.recordServer(
            vehicle,
            shooter,
            data,
            channel,
            VehicleWeaponAudioDiagnostics.Disposition.PLAYED,
            sound,
            "typed_vehicle_reload_owner=false",
        )
    }

    private data class SameClipKey(val vehicle: UUID, val listener: UUID, val sound: String)
    private const val SAME_CLIP_WINDOW_TICKS = 40
    private val recentClips = HashMap<SameClipKey, Long>()

    /**
     * The players seated where [weaponIdentity] is operated (any seat whose weapon list holds it). Falls back to the
     * controlling passenger when no seat lists the weapon (aliases, pods).
     */
    @JvmStatic
    fun operatorListeners(vehicle: VehicleEntity, weaponIdentity: String): List<ServerPlayer> {
        val crew = vehicle.passengers.filterIsInstance<ServerPlayer>()
        val operators = crew.filter { player ->
            val seat = vehicle.getSeatIndex(player)
            seat >= 0 && vehicle.getWeaponIds(seat).contains(weaponIdentity)
        }
        if (operators.isNotEmpty()) return operators
        return listOfNotNull(vehicle.controllingPassenger as? ServerPlayer)
    }

    /**
     * A one-off crew cue (a manual reload, a shell dropped down a mortar tube): heard only by [operator], never
     * broadcast to the players around the vehicle.
     */
    @JvmStatic
    @JvmOverloads
    fun playOperatorCue(operator: Entity?, sound: SoundEvent?, volume: Float = 1f, pitch: Float = 1f) {
        val player = operator as? ServerPlayer ?: return
        if (sound == null || sound.location == SoundEvents.EMPTY.location) return
        player.playNotifySound(sound, net.minecraft.sounds.SoundSource.PLAYERS, volume, pitch)
    }

    /** Emits exactly one vehicle reload transition, to the crew operating the weapon only. */
    private fun emit(
        vehicle: VehicleEntity,
        data: GunData,
        soundInfo: SoundInfo,
        reloadDurationTicks: Int,
    ) {
        val local = soundInfo.vehicleReload
        val remote = soundInfo.vehicleReload3p
        val hasLocal = isConfiguredVehicleReloadSound(local)
        val hasRemote = isConfiguredVehicleReloadSound(remote)
        val controller = vehicle.controllingPassenger as? LivingEntity
        if (!hasLocal && !hasRemote) {
            VehicleWeaponAudioDiagnostics.recordServer(
                vehicle,
                controller,
                data,
                "TYPED_VEHICLE_RELOAD",
                VehicleWeaponAudioDiagnostics.Disposition.NO_SOUND_CONFIGURED,
            )
            return
        }
        // Reload audio belongs to the crew working the weapon: the interior clip when authored, the exterior clip
        // otherwise, heard only by the players in the seats that operate this weapon (never by bystanders).
        val selectedSound = if (hasLocal) local else remote
        when (val claim = claimVehicleReloadSound(vehicle, data, vehicle.level().gameTime)) {
            VehicleReloadSoundClaim.CLAIMED -> Unit
            VehicleReloadSoundClaim.DUPLICATE -> {
                VehicleWeaponAudioDiagnostics.recordServer(
                    vehicle,
                    controller,
                    data,
                    "TYPED_VEHICLE_RELOAD_LOCAL",
                    VehicleWeaponAudioDiagnostics.Disposition.DUPLICATE_SUPPRESSED,
                    selectedSound,
                    "claim=${claim.name}",
                )
                return
            }
            else -> {
                VehicleWeaponAudioDiagnostics.recordServer(
                    vehicle,
                    controller,
                    data,
                    "TYPED_VEHICLE_RELOAD_LOCAL",
                    VehicleWeaponAudioDiagnostics.Disposition.CLAIM_REJECTED,
                    selectedSound,
                    "claim=${claim.name}",
                )
                return
            }
        }

        val key = vehicleReloadSoundKey(vehicle, data) ?: return
        val cycle = vehicleReloadSoundCycles[key] ?: return
        val gameTime = vehicle.level().gameTime
        var listeners = operatorListeners(vehicle, key.weaponIdentity)
        // Several weapons of one seat often share a clip (rocket pods, twin launchers) and reload together:
        // a player already hearing that clip from this vehicle does not get a second, overlapping copy.
        val clipTicks = soundInfo.vehicleReloadClipDurationTicks.takeIf { it > 0 } ?: SAME_CLIP_WINDOW_TICKS
        listeners = listeners.filter { player ->
            val clipKey = SameClipKey(vehicle.uuid, player.uuid, selectedSound.location.toString())
            val last = recentClips[clipKey]
            if (last != null && gameTime - last in 0 until clipTicks) false
            else { recentClips[clipKey] = gameTime; true }
        }
        if (recentClips.size > 256) recentClips.entries.removeIf { gameTime - it.value > 1200 }
        retainVehicleReloadSoundEmission(
            vehicle, data, selectedSound, listeners, reloadDurationTicks,
            soundInfo.vehicleReloadClipDurationTicks, true,
        )
        VehicleWeaponAudioDiagnostics.recordServer(
            vehicle, controller, data,
            "TYPED_VEHICLE_RELOAD_LOCAL",
            VehicleWeaponAudioDiagnostics.Disposition.PLAYED, selectedSound,
            "cycle=${cycle.playbackId} listener_count=${listeners.size}",
        )
        if (listeners.isEmpty()) return
        val message = VehicleReloadSoundMessage(
            cycle.playbackId, vehicle.level().dimension().location(), vehicle.id, vehicle.uuid,
            key.weaponIdentity, key.reloadRevision, selectedSound.location,
            VehicleReloadSoundTiming.ticksUntilCompletion(data.reload.time()).coerceIn(1, 1200),
            true, false,
        )
        for (listener in listeners) sendPacketTo(PacketDistributor.PLAYER.with { listener }, message)
    }
}
