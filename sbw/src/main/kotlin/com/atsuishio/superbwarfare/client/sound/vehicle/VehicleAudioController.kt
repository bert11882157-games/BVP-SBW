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
import kotlin.math.sqrt

/**
 * Voices vehicles that have an authored [VehicleAudioProfiles.Profile]: one state machine per vehicle.
 *
 *  - Engine: OFF -> (start clip; idle/drive loops fade in over the end of it) -> ON (idle and drive layers
 *    cross-faded and pitched by RPM) -> (stop clip; loops fade out) -> OFF. A vehicle first seen already running
 *    skips the start clip. The load is throttle for aircraft, collective for helicopters and input/speed on the
 *    ground; the engine RPM follows it at the profile's spool rate.
 *  - Aircraft layers: low-frequency roar / afterburner (`boost`), the far sound (`distant`, taking over from the near
 *    engine with distance), rotor blades (`rotor`) and the cockpit ambience for the crew (`interior`).
 *  - Tracks: a loop by ground speed.
 *  - Turret: start clip, a loop while the turret slews (volume and pitch by slew rate), stop clip when it settles.
 *
 * Sound travels at [SpatialAudioPlayer.SOUND_BLOCKS_PER_TICK]: every voice sits where its vehicle was when the sound
 * now reaching the listener left it, with the RPM of that moment, and start/stop clips arrive as late (a jet is heard
 * behind where it is seen). Every voice uses the sqrt distance law of [SpatialAudioPlayer.gainAt] (the crew hears the
 * interior mix instead), is Doppler-shifted by [SpatialDoppler], and only the nearest [MAX_VEHICLES] voiced vehicles
 * play at all.
 */
@Mod.EventBusSubscriber(modid = com.atsuishio.superbwarfare.Mod.MODID, value = [Dist.CLIENT])
object VehicleAudioController {
    private const val MAX_VEHICLES = 12
    private const val LOOP_FADE_OUT_TICKS = 8f
    private const val TURRET_SETTLE_TICKS = 3
    /** Ticks of source history kept for the propagation delay (100 ticks = ~1700 blocks of travel). */
    private const val HISTORY = 100
    private const val CREATE_GAIN = 0.004f
    private const val KEEP_GAIN = 0.002f
    /** Ticks the engine must read off before it counts as stopped (no stop/start clips on a momentary drop). */
    private const val OFF_HOLD_TICKS = 30

    private class State(val vehicle: VehicleEntity, val profile: VehicleAudioProfiles.Profile) {
        var known = false
        var running = false
        var startTick = Long.MIN_VALUE
        /** Clock at which the loops of a stopped engine go quiet (when the stop reaches the listener). */
        var releaseAt = Long.MIN_VALUE
        /** Clock of the last stop; until it is heard, a restart must not cut the run still on its way. */
        var stopTick = Long.MIN_VALUE
        var offTicks = 0
        /** Emission point of the sound now heard (loops start there). */
        var sx = vehicle.x
        var sy = vehicle.y
        var sz = vehicle.z
        var rpm = 0f
        var boostLevel = 0f
        var idle: Voice? = null
        var drive: Voice? = null
        var boost: Voice? = null
        var distant: Voice? = null
        var rotor: Voice? = null
        var interior: Voice? = null
        var tracks: Voice? = null
        var turret: Voice? = null
        var turretStill = 0
        var lastYaw = vehicle.turretYRot
        var lastPitch = vehicle.turretXRot
        var seen = 0L

        val hx = DoubleArray(HISTORY)
        val hy = DoubleArray(HISTORY)
        val hz = DoubleArray(HISTORY)
        val hrpm = FloatArray(HISTORY)
        var head = -1
        var filled = 0

        fun record(x: Double, y: Double, z: Double, value: Float) {
            head = (head + 1) % HISTORY
            hx[head] = x; hy[head] = y; hz[head] = z; hrpm[head] = value
            if (filled < HISTORY) filled++
        }

        /** Ring index of the sample [ticks] ago. */
        fun ago(ticks: Int) = (head - ticks + HISTORY) % HISTORY

        fun voices() = listOfNotNull(idle, drive, boost, distant, rotor, interior, tracks, turret)
    }

    private val states = LinkedHashMap<UUID, State>()
    private var firstStartLogged = false
    /** Unpaused client ticks: the clock SoundManager.playDelayed counts in (game time can jump on a time sync). */
    private var clock = 0L

    private fun typeId(vehicle: VehicleEntity): ResourceLocation? = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)

    private fun profile(vehicle: VehicleEntity) = VehicleAudioProfiles.forType(typeId(vehicle))

    /** True when the engine and track loops of [vehicle] are voiced here (the native loops must stay silent). */
    @JvmStatic
    fun ownsEngine(vehicle: VehicleEntity): Boolean = profile(vehicle)?.engine != null

    /** True when turret slewing of [vehicle] is voiced here (no per-tick turret one-shots). */
    @JvmStatic
    fun ownsTurret(vehicle: VehicleEntity): Boolean = profile(vehicle)?.turret != null

    /** True while this controller voices the vehicle [id] (other far-engine voices must stay out). */
    @JvmStatic
    fun voices(id: UUID): Boolean = states.containsKey(id)

    /** The authored far layer of [vehicle], for voicing it beyond the loaded entities. */
    @JvmStatic
    fun distantLayer(vehicle: VehicleEntity): VehicleAudioProfiles.Layer? = profile(vehicle)?.distant

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
        val now = ++clock

        val candidates = ArrayList<Pair<VehicleEntity, Double>>()
        for (entity in level.entitiesForRendering()) {
            val vehicle = entity as? VehicleEntity ?: continue
            if (vehicle.isRemoved) continue
            val profile = profile(vehicle) ?: continue
            val reach = profile.reach + 16f
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

    /**
     * Engine on: a pilot/driver in seat 0 (VehicleEntity has no controlling passenger; seat 0 drives) or the engine
     * turning. For fixed-wing aircraft engineRunning() is thrust > 0, which idle throttle drops to: idling is RPM, not
     * a shutdown.
     */
    private fun engineOn(vehicle: VehicleEntity): Boolean {
        if (vehicle.isWreck || vehicle.health <= 0f) return false
        return vehicle.getNthEntity(0) != null || vehicle.engineRunning()
    }

    private fun load(vehicle: VehicleEntity): Float {
        if (vehicle.isFixedWingFlightVehicle()) {
            return vehicle.getVehicleFlightInstrumentSnapshot(1f).throttle.toFloat().coerceIn(0f, 1f)
        }
        val computed = vehicle.computed()
        if (computed?.engineType == EngineType.HELICOPTER) return vehicle.helicopterEngineLoad().coerceIn(0f, 1f)
        val speed = vehicle.deltaMovement.horizontalDistance().toFloat()
        val input = if (vehicle.forwardInputDown || vehicle.backInputDown) 0.55f else 0f
        return max(input, (speed / 0.5f).coerceIn(0f, 1f))
    }

    private fun afterburner(vehicle: VehicleEntity): Boolean =
        vehicle.isFixedWingFlightVehicle() &&
            vehicle.getVehicleFlightInstrumentSnapshot(1f).controlSurfaces?.afterburnerActive == true

    /** RPM one tick on toward [target]: at most 1/(seconds * 20) per tick, instantly when the time is 0. */
    @JvmStatic
    fun spool(rpm: Float, target: Float, upSeconds: Float, downSeconds: Float): Float {
        val seconds = if (target > rpm) upSeconds else downSeconds
        if (seconds <= 0f) return target
        val step = 1f / (seconds * 20f)
        // arrive exactly (float steps would otherwise stop a hair short of the target)
        return if (abs(target - rpm) <= step * 1.0001f) target else rpm + (target - rpm).coerceIn(-step, step)
    }

    /**
     * Ticks ago at which the sound reaching a listener [distance] blocks away left the source; [distances] gives the
     * listener distance of the source k ticks ago. The newest emission that has had time to arrive.
     */
    @JvmStatic
    fun propagationLag(samples: Int, distances: (Int) -> Double): Int {
        for (k in 0 until samples) {
            if (distances(k) <= (k + 0.5) * SpatialAudioPlayer.SOUND_BLOCKS_PER_TICK) return k
        }
        return (samples - 1).coerceAtLeast(0)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        if (b <= a) return if (x >= b) 1f else 0f
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun lerp(t: Float, range: FloatArray) = Mth.lerp(t, range[0], range[1])

    private fun update(mc: Minecraft, state: State, camera: Vec3, now: Long) {
        val vehicle = state.vehicle
        val profile = state.profile
        val crew = mc.player?.rootVehicle === vehicle
        val rawOn = engineOn(vehicle)
        state.offTicks = if (rawOn) 0 else state.offTicks + 1
        // a running engine stays on through a short drop; a wreck stops at once
        val on = rawOn || state.running && state.offTicks < OFF_HOLD_TICKS && !vehicle.isWreck && vehicle.health > 0f
        val engine = profile.engine

        // engine RPM follows the load at the spool rate; history for the propagation delay
        state.rpm = spool(state.rpm, if (on) load(vehicle) else 0f, engine?.spoolUp ?: 0f, engine?.spoolDown ?: 0f)
        state.record(vehicle.x, vehicle.y + vehicle.bbHeight * 0.4, vehicle.z, state.rpm)
        val lag = if (crew) 0 else propagationLag(state.filled) { k ->
            val i = state.ago(k)
            val dx = state.hx[i] - camera.x; val dy = state.hy[i] - camera.y; val dz = state.hz[i] - camera.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        val at = state.ago(lag)
        val sx = state.hx[at]; val sy = state.hy[at]; val sz = state.hz[at]
        state.sx = sx; state.sy = sy; state.sz = sz
        val distance = if (crew) 0.0 else sqrt((sx - camera.x).let { it * it } + (sy - camera.y).let { it * it } +
            (sz - camera.z).let { it * it })
        val rpm = state.hrpm[at]
        val heard = now - lag

        engine?.let {
            val gain = if (crew) engine.interiorVolume else
                (SpatialAudioPlayer.gainAt(distance, engine.range.toDouble()) * engine.volume).toFloat()
            if (!state.known) {
                state.known = true
                state.running = on
                state.startTick = Long.MIN_VALUE
            } else if (on && !state.running) {
                state.running = true
                state.startTick = now
                state.releaseAt = Long.MIN_VALUE
                engine.start?.let { oneShot(mc, it, vehicle, gain, lag) }
                if (!firstStartLogged) {
                    firstStartLogged = true
                    com.atsuishio.superbwarfare.Mod.LOGGER.info("Vehicle audio: engine start voiced for {} ({} profiles)",
                        typeId(vehicle), VehicleAudioProfiles.count())
                }
            } else if (!on && state.running) {
                state.running = false
                engine.stop?.let { oneShot(mc, it, vehicle, gain, lag) }
                state.releaseAt = now + lag
                state.stopTick = now
            }
            if (!state.running && state.releaseAt != Long.MIN_VALUE && now >= state.releaseAt) {
                state.releaseAt = Long.MIN_VALUE
                for (voice in listOfNotNull(state.idle, state.drive, state.boost, state.distant, state.rotor,
                        state.interior)) voice.release()
                state.idle = null; state.drive = null; state.boost = null; state.distant = null
                state.rotor = null; state.interior = null
            }
            if (state.running || state.releaseAt != Long.MIN_VALUE) {
                val startTicks = engine.startSeconds * 20f
                // the previous run is still on its way until its stop is heard; then the new start ramps in
                val intro = if (state.startTick == Long.MIN_VALUE || engine.start == null || heard < state.stopTick) 1f
                    else ((heard - state.startTick - 0.6f * startTicks) / (0.4f * startTicks).coerceAtLeast(1f))
                        .coerceIn(0f, 1f)
                val far = profile.distant
                val farWeight = if (far == null || crew) 0f else
                    smoothstep(far.near * 0.5f, far.near * 1.5f, distance.toFloat())
                val nearShare = 1f - 0.75f * farWeight
                val hasDrive = engine.drive != null
                engine.idle?.let { sound ->
                    val idleShare = if (hasDrive) Mth.lerp(rpm, 1f, 0.35f) else 1f
                    state.idle = layer(mc, state, state.idle, sound,
                        gain * idleShare * lerp(rpm, engine.idleVolume) * nearShare * intro, lerp(rpm, engine.idlePitch))
                }
                engine.drive?.let { sound ->
                    state.drive = layer(mc, state, state.drive, sound,
                        gain * lerp(rpm, engine.driveVolume) * nearShare * intro, lerp(rpm, engine.drivePitch))
                }
                profile.boost?.let { b ->
                    val wanted = max(if (afterburner(vehicle)) 1f else 0f, smoothstep(b.from, 1f, rpm))
                    state.boostLevel += (wanted - state.boostLevel) * 0.08f
                    val base = if (crew) engine.interiorVolume * 0.7f else
                        SpatialAudioPlayer.gainAt(distance, b.range.toDouble()).toFloat()
                    state.boost = layer(mc, state, state.boost, b.loop,
                        base * lerp(rpm, b.volume) * state.boostLevel * intro, lerp(rpm, b.pitch))
                }
                far?.let { d ->
                    state.distant = layer(mc, state, state.distant, d.loop,
                        SpatialAudioPlayer.gainAt(distance, d.range.toDouble()).toFloat() * lerp(rpm, d.volume) *
                            farWeight * intro, lerp(rpm, d.pitch))
                }
                profile.rotor?.let { r ->
                    val base = if (crew) engine.interiorVolume else
                        SpatialAudioPlayer.gainAt(distance, r.range.toDouble()).toFloat()
                    state.rotor = layer(mc, state, state.rotor, r.loop, base * lerp(rpm, r.volume) * intro,
                        lerp(rpm, r.pitch))
                }
                profile.interior?.let { i ->
                    state.interior = layer(mc, state, state.interior, i.loop,
                        if (crew) lerp(rpm, i.volume) * intro else 0f, lerp(rpm, i.pitch))
                }
            }
        }

        profile.tracks?.let { tracks ->
            val speed = vehicle.deltaMovement.horizontalDistance().toFloat()
            val norm = (speed / tracks.fullSpeed).coerceIn(0f, 1f)
            if (norm > 0.03f && !vehicle.isWreck) {
                val gain = if (crew) tracks.volume * 0.8f else
                    (SpatialAudioPlayer.gainAt(distance, tracks.range.toDouble()) * tracks.volume).toFloat()
                val v = state.tracks.alive() ?: loop(mc, tracks.loop, state).also { state.tracks = it }
                v.target(gain * norm, 0.85f + 0.3f * norm)
            } else { state.tracks?.release(); state.tracks = null }
        }

        profile.turret?.let { turret ->
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
                    turret.start?.let { oneShot(mc, it, vehicle, gain, lag) }
                    state.turret = loop(mc, turret.loop, state)
                }
                state.turret?.target(gain * (0.45f + 0.55f * norm), 0.92f + 0.16f * norm)
            } else if (state.turret != null && ++state.turretStill >= TURRET_SETTLE_TICKS) {
                state.turret?.release(); state.turret = null
                turret.stop?.let { oneShot(mc, it, vehicle, gain, lag) }
            }
        }

        // every loop sits where the sound now heard was emitted
        for (voice in state.voices()) voice.place(sx, sy, sz)
    }

    /** Keeps a loop at [gain] / [pitch]; starts it when it becomes audible and releases it when it is not. */
    private fun layer(mc: Minecraft, state: State, current: Voice?, sound: ResourceLocation, gain: Float,
                      pitch: Float): Voice? {
        val alive = current.alive()
        if (alive == null && gain < CREATE_GAIN || alive != null && gain < KEEP_GAIN) {
            current?.release()
            return null
        }
        val voice = alive ?: loop(mc, sound, state)
        voice.target(gain, pitch)
        return voice
    }

    private fun Voice?.alive(): Voice? = this?.takeIf { !it.isStopped && !it.releasing }

    private fun release(mc: Minecraft, state: State, hard: Boolean) {
        for (voice in state.voices()) {
            if (hard) { voice.stopNow(); mc.soundManager.stop(voice) } else voice.release()
        }
        state.idle = null; state.drive = null; state.boost = null; state.distant = null; state.rotor = null
        state.interior = null; state.tracks = null; state.turret = null
    }

    private fun loop(mc: Minecraft, sound: ResourceLocation, state: State): Voice =
        Voice(SoundEvent.createVariableRangeEvent(sound), state.vehicle, true, 0f, false).also {
            it.place(state.sx, state.sy, state.sz)
            it.snap()
            mc.soundManager.play(it)
        }

    private fun oneShot(mc: Minecraft, sound: ResourceLocation, vehicle: VehicleEntity, gain: Float, lag: Int) {
        if (gain < 0.01f) return
        val voice = Voice(SoundEvent.createVariableRangeEvent(sound), vehicle, false, gain, true)
        if (lag > 0) mc.soundManager.playDelayed(voice, lag) else mc.soundManager.play(voice)
    }

    /**
     * A voice of one vehicle; the controller sets target level, pitch and (for loops) the emission point, the voice
     * eases toward them. One-shots ride on the vehicle itself.
     */
    private class Voice(event: SoundEvent, val vehicle: VehicleEntity, loop: Boolean, level: Float,
                        private val rideVehicle: Boolean) :
        AbstractTickableSoundInstance(event, SoundSource.NEUTRAL, RandomSource.create()), DopplerSound,
        AttributedVehicleSound {
        private var targetVolume = level
        private var targetPitch = 1f
        private var px = vehicle.x
        private var py = vehicle.y + vehicle.bbHeight * 0.4
        private var pz = vehicle.z
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

        fun place(x: Double, y: Double, z: Double) { px = x; py = y; pz = z }

        /** Moves the voice to its emission point now (before it first plays). */
        fun snap() = follow()

        fun release() { releasing = true }
        fun stopNow() = stop()

        private fun follow() {
            if (rideVehicle) { x = vehicle.x; y = vehicle.y + vehicle.bbHeight * 0.4; z = vehicle.z }
            else { x = px; y = py; z = pz }
        }

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
