package com.atsuishio.superbwarfare.client.sound.vehicle

import com.atsuishio.superbwarfare.client.sound.AttributedVehicleSound
import com.atsuishio.superbwarfare.client.sound.spatial.DopplerSound
import com.atsuishio.superbwarfare.client.sound.spatial.SpatialAudioPlayer
import com.atsuishio.superbwarfare.client.sound.spatial.SpatialDoppler
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Voices vehicles that have an authored [VehicleAudioProfiles.Profile]: one state machine per vehicle.
 *
 *  - Engine: OFF -> (start clip; idle/drive loops fade in over the end of it) -> ON (idle and drive layers
 *    cross-faded and pitched by load) -> (stop clip; loops fade out) -> OFF. A vehicle first seen already running
 *    skips the start clip. Load is throttle for aircraft, power for helicopters, and input/speed on the ground.
 *  - Tracks: a loop by ground speed.
 *  - Turret: start clip, a loop while the turret slews (volume and pitch by slew rate), stop clip when it settles.
 *
 * Every voice follows its vehicle, uses the sqrt distance law of [SpatialAudioPlayer.gainAt] (the crew hears the
 * interior level instead), is Doppler-shifted by [SpatialDoppler], and only the nearest [MAX_VEHICLES] voiced
 * vehicles play at all.
 */
@Mod.EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID, value = [Dist.CLIENT])
object VehicleAudioController {
    private const val MAX_VEHICLES = 12
    private const val LOOP_FADE_OUT_TICKS = 8f
    private const val TURRET_SETTLE_TICKS = 3

    private class State(val vehicle: VehicleEntity, val profile: VehicleAudioProfiles.Profile) {
        var known = false
        var running = false
        var startTick = Long.MIN_VALUE
        var idle: Voice? = null
        var drive: Voice? = null
        var tracks: Voice? = null
        var turret: Voice? = null
        var turretStill = 0
        var lastYaw = vehicle.turretYRot
        var lastPitch = vehicle.turretXRot
        var seen = 0L
    }

    private val states = LinkedHashMap<UUID, State>()

    private fun typeId(vehicle: VehicleEntity): ResourceLocation? = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)

    private fun profile(vehicle: VehicleEntity) = VehicleAudioProfiles.forType(typeId(vehicle))

    /** True when the engine and track loops of [vehicle] are voiced here (the native loops must stay silent). */
    @JvmStatic
    fun ownsEngine(vehicle: VehicleEntity): Boolean = profile(vehicle)?.engine != null

    /** True when turret slewing of [vehicle] is voiced here (no per-tick turret one-shots). */
    @JvmStatic
    fun ownsTurret(vehicle: VehicleEntity): Boolean = profile(vehicle)?.turret != null

    fun clear() {
        val mc = Minecraft.getInstance()
        for (state in states.values) release(mc, state, hard = true)
        states.clear()
    }

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null) { if (states.isNotEmpty()) clear(); return }
        if (mc.isPaused || VehicleAudioProfiles.count() == 0) return
        val camera = mc.gameRenderer.mainCamera.position
        val now = level.gameTime

        val candidates = ArrayList<Pair<VehicleEntity, Double>>()
        for (entity in level.entitiesForRendering()) {
            val vehicle = entity as? VehicleEntity ?: continue
            if (vehicle.isRemoved) continue
            val profile = profile(vehicle) ?: continue
            val reach = maxOf(profile.engine?.range ?: 0f, profile.tracks?.range ?: 0f, profile.turret?.range ?: 0f) + 16f
            val d2 = vehicle.position().distanceToSqr(camera)
            val crew = player.rootVehicle === vehicle
            if (crew || d2 <= reach.toDouble() * reach) candidates.add(vehicle to if (crew) -1.0 else d2)
        }
        candidates.sortBy { it.second }
        for ((vehicle, _) in candidates.take(MAX_VEHICLES)) {
            val profile = profile(vehicle) ?: continue
            val state = states[vehicle.uuid]?.takeIf { it.vehicle === vehicle && it.profile === profile }
                ?: State(vehicle, profile).also { old ->
                    states.remove(vehicle.uuid)?.let { release(mc, it, hard = true) }
                    states[vehicle.uuid] = old
                }
            state.seen = now
            update(mc, state, camera, now)
        }
        val iterator = states.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            if (state.seen != now) { release(mc, state, hard = false); iterator.remove() }
        }
    }

    private fun engineOn(vehicle: VehicleEntity): Boolean {
        if (vehicle.isWreck || vehicle.health <= 0f) return false
        if (vehicle.isFixedWingFlightVehicle()) return vehicle.engineRunning()
        return vehicle.controllingPassenger != null || vehicle.engineRunning()
    }

    private fun load(vehicle: VehicleEntity): Float {
        if (vehicle.isFixedWingFlightVehicle()) {
            return vehicle.getVehicleFlightInstrumentSnapshot(1f).throttle.toFloat().coerceIn(0f, 1f)
        }
        val computed = vehicle.computed()
        if (computed?.engineType == EngineType.HELICOPTER) return abs(vehicle.power).coerceIn(0f, 1f)
        val speed = vehicle.deltaMovement.horizontalDistance().toFloat()
        val input = if (vehicle.forwardInputDown || vehicle.backInputDown) 0.55f else 0f
        return max(input, (speed / 0.5f).coerceIn(0f, 1f))
    }

    private fun update(mc: Minecraft, state: State, camera: Vec3, now: Long) {
        val vehicle = state.vehicle
        val crew = mc.player?.rootVehicle === vehicle
        val distance = vehicle.position().distanceTo(camera)
        val on = engineOn(vehicle)

        state.profile.engine?.let { engine ->
            val gain = if (crew) engine.interiorVolume else
                (SpatialAudioPlayer.gainAt(distance, engine.range.toDouble()) * engine.volume).toFloat()
            if (!state.known) {
                state.known = true
                state.running = on
                state.startTick = Long.MIN_VALUE
            } else if (on && !state.running) {
                state.running = true
                state.startTick = now
                engine.start?.let { oneShot(mc, it, vehicle, gain) }
            } else if (!on && state.running) {
                state.running = false
                engine.stop?.let { oneShot(mc, it, vehicle, gain) }
                state.idle?.release(); state.idle = null
                state.drive?.release(); state.drive = null
            }
            if (state.running) {
                val startTicks = engine.startSeconds * 20f
                val intro = if (state.startTick == Long.MIN_VALUE || engine.start == null) 1f else
                    ((now - state.startTick - 0.6f * startTicks) / (0.4f * startTicks).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val l = load(vehicle)
                val hasDrive = engine.drive != null
                engine.idle?.let {
                    val v = state.idle.alive() ?: loop(mc, it, vehicle).also { voice -> state.idle = voice }
                    val idleShare = if (hasDrive) Mth.lerp(l, 1f, 0.35f) else 1f
                    v.target(gain * idleShare * intro, Mth.lerp(l, engine.idlePitch[0], engine.idlePitch[1]))
                }
                engine.drive?.let {
                    val v = state.drive.alive() ?: loop(mc, it, vehicle).also { voice -> state.drive = voice }
                    v.target(gain * Mth.lerp(l, engine.driveVolume[0], engine.driveVolume[1]) * intro,
                        Mth.lerp(l, engine.drivePitch[0], engine.drivePitch[1]))
                }
            }
        }

        state.profile.tracks?.let { tracks ->
            val speed = vehicle.deltaMovement.horizontalDistance().toFloat()
            val norm = (speed / tracks.fullSpeed).coerceIn(0f, 1f)
            if (norm > 0.03f && !vehicle.isWreck) {
                val gain = if (crew) tracks.volume * 0.8f else
                    (SpatialAudioPlayer.gainAt(distance, tracks.range.toDouble()) * tracks.volume).toFloat()
                val v = state.tracks.alive() ?: loop(mc, tracks.loop, vehicle).also { state.tracks = it }
                v.target(gain * norm, 0.85f + 0.3f * norm)
            } else { state.tracks?.release(); state.tracks = null }
        }

        state.profile.turret?.let { turret ->
            val rate = max(abs(Mth.wrapDegrees(vehicle.turretYRot - state.lastYaw)),
                abs(vehicle.turretXRot - state.lastPitch))
            state.lastYaw = vehicle.turretYRot
            state.lastPitch = vehicle.turretXRot
            val norm = (rate * 20f / turret.fullRate).coerceIn(0f, 1f)
            val gain = if (crew) turret.volume else
                (SpatialAudioPlayer.gainAt(distance, turret.range.toDouble()) * turret.volume).toFloat()
            if (norm > 0.04f && !vehicle.isWreck) {
                state.turretStill = 0
                val voice = state.turret.alive()
                if (voice == null) {
                    turret.start?.let { oneShot(mc, it, vehicle, gain) }
                    state.turret = loop(mc, turret.loop, vehicle)
                }
                state.turret?.target(gain * (0.45f + 0.55f * norm), 0.92f + 0.16f * norm)
            } else if (state.turret != null && ++state.turretStill >= TURRET_SETTLE_TICKS) {
                state.turret?.release(); state.turret = null
                turret.stop?.let { oneShot(mc, it, vehicle, gain) }
            }
        }
    }

    private fun Voice?.alive(): Voice? = this?.takeIf { !it.isStopped && !it.releasing }

    private fun release(mc: Minecraft, state: State, hard: Boolean) {
        for (voice in listOfNotNull(state.idle, state.drive, state.tracks, state.turret)) {
            if (hard) { voice.stopNow(); mc.soundManager.stop(voice) } else voice.release()
        }
        state.idle = null; state.drive = null; state.tracks = null; state.turret = null
    }

    private fun loop(mc: Minecraft, sound: ResourceLocation, vehicle: VehicleEntity): Voice =
        Voice(SoundEvent.createVariableRangeEvent(sound), vehicle, true, 0f).also { mc.soundManager.play(it) }

    private fun oneShot(mc: Minecraft, sound: ResourceLocation, vehicle: VehicleEntity, gain: Float) {
        if (gain < 0.01f) return
        mc.soundManager.play(Voice(SoundEvent.createVariableRangeEvent(sound), vehicle, false, gain))
    }

    /** A voice pinned to its vehicle; the controller sets target level and pitch, the voice eases toward them. */
    private class Voice(event: SoundEvent, val vehicle: VehicleEntity, loop: Boolean, level: Float) :
        AbstractTickableSoundInstance(event, SoundSource.NEUTRAL, RandomSource.create()), DopplerSound,
        AttributedVehicleSound {
        private var targetVolume = level
        private var targetPitch = 1f
        var releasing = false
            private set

        init {
            looping = loop
            delay = 0
            attenuation = SoundInstance.Attenuation.NONE
            volume = level
            follow()
        }

        fun target(level: Float, pitch: Float) {
            targetVolume = level.coerceIn(0f, 1f)
            targetPitch = pitch.coerceIn(0.5f, 2f)
        }

        fun release() { releasing = true }
        fun stopNow() = stop()

        private fun follow() { x = vehicle.x; y = vehicle.y + vehicle.bbHeight * 0.4; z = vehicle.z }

        override fun canStartSilent() = true

        override fun tick() {
            if (vehicle.isRemoved) { stop(); return }
            follow()
            if (!looping) return
            if (releasing) {
                volume -= max(volume / LOOP_FADE_OUT_TICKS, 0.02f)
                if (volume <= 0.001f) { volume = 0f; stop() }
                return
            }
            volume += (targetVolume - volume) * 0.35f
            pitch += (targetPitch - pitch) * 0.25f
        }

        override fun dopplerVelocity(): Vec3? {
            val player = Minecraft.getInstance().player
            if (player != null && player.rootVehicle === vehicle) return null
            return SpatialDoppler.entityVelocity(vehicle)
        }

        override fun eliteSourceEntity(): UUID = vehicle.uuid
        override fun eliteChannel() = "vehicle_audio"
    }
}
