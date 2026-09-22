package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.api.weapon.AudioPlaybackRegistry
import com.atsuishio.superbwarfare.api.weapon.ReloadPlaybackWindow
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.network.message.receive.VehicleReloadSoundMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import java.util.UUID
import com.atsuishio.superbwarfare.Mod
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent
import net.minecraftforge.client.event.sound.PlayStreamingSourceEvent

/** Client playback lives exactly as long as its originating reload cycle and vehicle. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object VehicleReloadSounds {
    private val instances = AudioPlaybackRegistry<ReloadSound> {
        it.cancelPlayback()
    }

    fun handle(message: VehicleReloadSoundMessage) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (level.dimension().location() != message.dimension) return
        if (message.stop) {
            instances.stop(message.cycleId)
            trace(message, "CLIENT_STOP_RECEIVED")
            return
        }
        val vehicle = level.getEntity(message.vehicleId) as? VehicleEntity ?: return
        if (vehicle.uuid != message.vehicleUuid || message.remainingTicks <= 0) return
        var sound: ReloadSound? = null
        val started = instances.start(message.cycleId) {
            ReloadSound(message, vehicle).also { sound = it }
        }
        if (started) sound?.reconcile(false)
        else trace(message, "CLIENT_DUPLICATE_SUPPRESSED")
    }

    private fun trace(message: VehicleReloadSoundMessage, event: String) {
        val level = Minecraft.getInstance().level ?: return
        EliteDiagnostics.recordClient(level.gameTime, "reload_playback", event,
            "entity_uuid", message.vehicleUuid, "weapon", message.weaponIdentity,
            "cycle", message.cycleId, "revision", message.reloadRevision,
            "remaining_ticks", message.remainingTicks, "local_only", message.localOnly)
    }

    fun detachListener(vehicleUuid: UUID) {
        instances.stopMatching { it.origin.vehicleUuid == vehicleUuid && it.origin.localOnly }
    }

    fun clear() = instances.clear()

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END && !Minecraft.getInstance().isPaused) {
            instances.forEachActive { it.reconcile(true) }
        }
    }

    // These callbacks run on the audio executor after an asynchronous buffer becomes ready.
    // A revoked instance must not be revived by the buffer's later channel.play() call.
    @SubscribeEvent
    fun sourceReady(event: PlaySoundSourceEvent) {
        val reload = event.sound as? ReloadSound ?: return
        if (!reload.playbackAllowed()) event.channel.stop()
    }

    @SubscribeEvent
    fun streamReady(event: PlayStreamingSourceEvent) {
        val reload = event.sound as? ReloadSound ?: return
        if (!reload.playbackAllowed()) event.channel.stop()
    }

    class ReloadSound(val origin: VehicleReloadSoundMessage, private val vehicle: VehicleEntity) :
        AbstractTickableSoundInstance(
            SoundEvent.createVariableRangeEvent(origin.soundEvent), SoundSource.PLAYERS,
            RandomSource.create(),
        ), AttributedVehicleSound {
        override fun eliteSourceEntity() = origin.vehicleUuid
        override fun eliteWeapon() = origin.weaponIdentity
        override fun eliteChannel() = "reload"
        override fun eliteCycle() = origin.cycleId
        override fun eliteReloadRevision() = origin.reloadRevision
        private var remaining = origin.remainingTicks
        @Volatile private var cancelled = false
        private var played = false

        /** Audio-executor access reads only the monotonic cancellation flag. */
        fun playbackAllowed() = !cancelled
        override fun canPlaySound() = playbackAllowed()

        /** Terminate the tickable instance as well as its possibly asynchronous sound channel. */
        fun cancelPlayback() {
            if (cancelled) return
            cancelled = true
            remaining = 0
            stop()
            Minecraft.getInstance().soundManager.stop(this)
            trace(origin, "CLIENT_CANCELLED")
        }

        init {
            looping = false
            delay = 0
            volume = if (origin.localOnly) 3f else 2f
            pitch = 1f
            relative = origin.localOnly
            attenuation = if (origin.localOnly) SoundInstance.Attenuation.NONE else SoundInstance.Attenuation.LINEAR
            updatePosition()
        }

        private fun updatePosition() {
            x = if (origin.localOnly) 0.0 else vehicle.x
            y = if (origin.localOnly) 0.0 else vehicle.y
            z = if (origin.localOnly) 0.0 else vehicle.z
        }

        fun reconcile(advanceClock: Boolean) {
            if (cancelled) return
            val client = Minecraft.getInstance()
            if (advanceClock) remaining--
            val data = vehicle.getGunData(origin.weaponIdentity)
            val revision = data?.reload?.soundCycleRevision() ?: 0
            val decision = ReloadPlaybackWindow.evaluate(origin.reloadRevision, revision,
                data?.reloading() == true, data?.reload?.time() ?: 0, remaining,
                data != null && !vehicle.isRemoved && client.level === vehicle.level() &&
                    (!origin.localOnly || client.player?.vehicle === vehicle))
            val active = decision == ReloadPlaybackWindow.Decision.PLAY
            if (decision == ReloadPlaybackWindow.Decision.STOP) {
                cancelPlayback()
                instances.retire(origin.cycleId)
                trace(origin, "CLIENT_FINISHED")
                return
            }
            // A start packet can precede the corresponding entity-data update. Wait for the
            // exact revision rather than playing against an idle or superseded weapon.
            if (active && !played) {
                played = true
                client.soundManager.play(this)
                trace(origin, "CLIENT_START")
            }
            if (played && EliteDiagnostics.isClientEnabled()) {
                EliteDiagnostics.recordClient(vehicle.level().gameTime, "reload_playback", "CLIENT_STATE",
                    "entity_uuid", origin.vehicleUuid, "weapon", origin.weaponIdentity,
                    "cycle", origin.cycleId, "revision", revision, "reloading", active,
                    "remaining_ticks", data?.reload?.time(), "engine_active", client.soundManager.isActive(this))
            }
            updatePosition()
        }

        override fun tick() {
            if (!cancelled) updatePosition()
        }
    }
}
