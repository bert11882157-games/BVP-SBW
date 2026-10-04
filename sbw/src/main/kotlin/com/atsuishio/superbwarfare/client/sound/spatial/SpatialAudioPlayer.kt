package com.atsuishio.superbwarfare.client.sound.spatial

import com.atsuishio.superbwarfare.api.audio.SpatialAudio
import com.atsuishio.superbwarfare.client.sound.AttributedVehicleSound
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.network.message.receive.SpatialAudioMessage
import com.atsuishio.superbwarfare.tools.queueClientWorkIfDelayed
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Client half of [SpatialAudio]: variant choice, distance law, speed-of-sound delay, Doppler and the voice pool.
 */
object SpatialAudioPlayer {
    /** Full level inside this distance (blocks); beyond it gain = sqrt(REF / d). */
    const val REFERENCE_DISTANCE = 6.0
    /** Speed of sound in blocks per tick. */
    const val SOUND_BLOCKS_PER_TICK = 343.3 / 20.0
    private const val MIN_AUDIBLE = 0.012f

    /** Gain for a listener [distance] blocks away from a source audible to [maxRange]. */
    @JvmStatic
    fun gainAt(distance: Double, maxRange: Double): Double {
        val base = sqrt(REFERENCE_DISTANCE / max(distance, REFERENCE_DISTANCE))
        return base * (1.0 - smoothstep(0.75 * maxRange, maxRange, distance))
    }

    /** Near / far / very-far weights at [distance] (sum 1), cross-faded over 15% of the hearing range. */
    @JvmStatic
    fun bandWeights(distance: Double, nearRange: Double, farRange: Double, maxRange: Double): DoubleArray {
        val fade = 0.15 * maxRange
        val near = 1.0 - smoothstep(nearRange - fade / 2, nearRange + fade / 2, distance)
        val veryFar = smoothstep(farRange - fade / 2, farRange + fade / 2, distance)
        val far = (1.0 - near - veryFar).coerceAtLeast(0.0)
        return doubleArrayOf(near, far, veryFar)
    }

    /** Moves the weight of missing variants to the nearest authored one. */
    @JvmStatic
    fun remap(weights: DoubleArray, present: BooleanArray): DoubleArray {
        val out = weights.copyOf()
        // fallback preference per slot; a missing far layer goes to whichever neighbour dominates at this distance
        val order = arrayOf(intArrayOf(1, 2), if (weights[2] > weights[0]) intArrayOf(2, 0) else intArrayOf(0, 2),
            intArrayOf(1, 0))
        for (i in 0..2) {
            if (present[i] || out[i] == 0.0) continue
            val target = order[i].firstOrNull { present[it] } ?: continue
            out[target] += out[i]
            out[i] = 0.0
        }
        return out
    }

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        if (b <= a) return if (x >= b) 1.0 else 0.0
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    // ---------------------------------------------------------------- playback

    private val active = Array(SpatialAudio.Category.entries.size) { ArrayList<OneShot>() }
    private val lastPlayed = HashMap<Long, Long>()

    fun handle(message: SpatialAudioMessage) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val pos = Vec3(message.x, message.y, message.z)
        val source = if (message.sourceId >= 0) level.getEntity(message.sourceId) else null
        val isOperator = message.operatorId >= 0 && player.id == message.operatorId
        val isCrew = source != null && player.vehicle != null && player.rootVehicle === source.rootVehicle
        val category = SpatialAudio.Category.entries.getOrElse(message.category) { SpatialAudio.Category.WEAPON }
        // vehicle gunfire belongs to the ground / aircraft weapons slider of the vehicle mix
        val mix = if (category == SpatialAudio.Category.WEAPON)
            com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.weaponsOf(
                (source?.rootVehicle ?: source) as? com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)
            else null

        if (isOperator || isCrew) {
            // inside the vehicle / on the weapon: the interior variant, now, no distance loss or Doppler
            val firstPersonView = mc.options.cameraType.isFirstPerson || ClientEventHandler.zoomVehicle
            val interior = if (firstPersonView) message.firstPerson ?: message.near ?: message.far ?: message.veryFar
                else message.near ?: message.firstPerson ?: message.far ?: message.veryFar
            interior ?: return
            val relative = firstPersonView && message.firstPerson != null
            play(category, interior, pos, message.gain.coerceAtMost(1f), message.pitch, null, relative, message, mix)
            return
        }

        val camera = mc.gameRenderer.mainCamera.position
        val distance = camera.distanceTo(pos)
        if (distance > message.maxRange) return
        val gain = gainAt(distance, message.maxRange.toDouble()) * message.gain
        if (gain < MIN_AUDIBLE) return
        val slots = arrayOf(message.near, message.far, message.veryFar)
        val weights = remap(
            bandWeights(distance, message.nearRange.toDouble(), message.farRange.toDouble(), message.maxRange.toDouble()),
            BooleanArray(3) { slots[it] != null },
        )
        val velocity = source?.let { SpatialDoppler.entityVelocity(it) }
        val delay = (distance / SOUND_BLOCKS_PER_TICK).toInt()
        for (i in 0..2) {
            val sound = slots[i] ?: continue
            val volume = (gain * weights[i]).toFloat()
            if (volume < MIN_AUDIBLE) continue
            queueClientWorkIfDelayed(delay) {
                if (Minecraft.getInstance().level === level) play(category, sound, pos, volume, message.pitch, velocity, false, message, mix)
            }
        }
    }

    private fun play(
        category: SpatialAudio.Category, sound: ResourceLocation, pos: Vec3, volume: Float, pitch: Float,
        velocity: Vec3?, relative: Boolean, message: SpatialAudioMessage,
        mix: com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.Category? = null,
    ) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        // one copy of the same clip from the same source per tick (salvos, paired barrels)
        val key = (message.sourceId.toLong() shl 32) xor sound.hashCode().toLong()
        val now = level.gameTime
        if (message.sourceId >= 0 && lastPlayed.put(key, now) == now) return
        if (lastPlayed.size > 512) lastPlayed.entries.removeIf { now - it.value > 40 }

        val voices = active[category.ordinal]
        voices.removeIf { it.finished() }
        if (voices.size >= category.cap) {
            val quietest = voices.minByOrNull { it.level } ?: return
            if (quietest.level >= volume) return
            quietest.cut()
            voices.remove(quietest)
        }
        val instance = OneShot(SoundEvent.createVariableRangeEvent(sound), volume, pitch, pos, velocity, relative, message, mix)
        voices.add(instance)
        mc.soundManager.play(instance)
    }

    fun clear() {
        for (list in active) list.clear()
        lastPlayed.clear()
    }

    class OneShot(
        event: SoundEvent, val level: Float, pitch: Float, pos: Vec3, private val velocity: Vec3?,
        relative: Boolean, private val origin: SpatialAudioMessage,
        private val mix: com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.Category? = null,
    ) : AbstractTickableSoundInstance(event, SoundSource.BLOCKS, RandomSource.create()), DopplerSound,
        AttributedVehicleSound, com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.Tagged {
        override fun vehicleMixCategory() = mix
        private val started = System.nanoTime()

        init {
            volume = level
            this.pitch = pitch
            looping = false
            delay = 0
            this.relative = relative
            attenuation = SoundInstance.Attenuation.NONE
            if (relative) { x = 0.0; y = 0.0; z = 0.0 } else { x = pos.x; y = pos.y; z = pos.z }
        }

        override fun tick() {}
        override fun dopplerVelocity(): Vec3? = velocity
        override fun eliteSourceEntity(): UUID? = null
        override fun eliteWeapon() = origin.weapon
        override fun eliteChannel() = "spatial"

        fun cut() { stop(); Minecraft.getInstance().soundManager.stop(this) }

        /** Stopped, or past any plausible one-shot length (the engine drops finished channels silently). */
        fun finished() = isStopped || !Minecraft.getInstance().soundManager.isActive(this) &&
            System.nanoTime() - started > 250_000_000L
    }
}
