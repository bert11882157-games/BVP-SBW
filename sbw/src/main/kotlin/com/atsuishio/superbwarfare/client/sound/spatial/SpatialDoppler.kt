package com.atsuishio.superbwarfare.client.sound.spatial

import com.atsuishio.superbwarfare.mixins.ChannelAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.ChannelAccess
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.lwjgl.openal.AL10
import kotlin.math.sqrt

/**
 * Real OpenAL Doppler for [DopplerSound]s. Minecraft never sets source or listener velocities, so OpenAL's Doppler
 * (factor 1, speed of sound 343.3 units/s = blocks/s) is dormant; this sets each moving source's velocity relative
 * to the listener's own vehicle every tick (the listener velocity stays zero, so no other sound is affected).
 *
 * Relative speed is capped below the speed of sound (a head-on jet still rises at most ~2.2x in pitch).
 */
object SpatialDoppler {
    private const val MAX_RELATIVE_BLOCKS_PER_SECOND = 0.55 * 343.3

    /** Called from the sound engine's tick (client thread) with its live instance -> channel map. */
    @JvmStatic
    fun apply(channels: Map<SoundInstance, ChannelAccess.ChannelHandle>) {
        if (channels.isEmpty()) return
        val listener = listenerVelocity()
        for ((instance, handle) in channels) {
            if (instance !is DopplerSound || instance.isRelative) continue
            val v = instance.dopplerVelocity() ?: continue
            var vx = (v.x - listener.x) * 20.0
            var vy = (v.y - listener.y) * 20.0
            var vz = (v.z - listener.z) * 20.0
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            if (!speed.isFinite()) continue
            if (speed > MAX_RELATIVE_BLOCKS_PER_SECOND) {
                val k = MAX_RELATIVE_BLOCKS_PER_SECOND / speed
                vx *= k; vy *= k; vz *= k
            }
            val fx = vx.toFloat(); val fy = vy.toFloat(); val fz = vz.toFloat()
            handle.execute { channel ->
                val source = (channel as ChannelAccessor).`superbwarfare$source`()
                AL10.alSource3f(source, AL10.AL_VELOCITY, fx, fy, fz)
            }
        }
    }

    /** Velocity (blocks/tick) of the thing the listener rides, or the listener itself. */
    @JvmStatic
    fun listenerVelocity(): Vec3 {
        val camera = Minecraft.getInstance().cameraEntity ?: return Vec3.ZERO
        return entityVelocity(camera.rootVehicle)
    }

    /** Per-tick displacement: valid for remote (interpolated) entities, whose deltaMovement is often zero. */
    @JvmStatic
    fun entityVelocity(entity: Entity): Vec3 = Vec3(entity.x - entity.xo, entity.y - entity.yo, entity.z - entity.zo)
}
