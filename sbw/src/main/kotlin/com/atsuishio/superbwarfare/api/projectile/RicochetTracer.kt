package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity
import com.atsuishio.superbwarfare.network.message.receive.RicochetTracerMessage
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3

/**
 * Ricochets are cosmetic (owner 2026-09-30): the armor eats the shot on the server, and clients get a tracer leaving the
 * plate along the reflected path at half the round's speed, which hits nothing.
 */
object RicochetTracer {
    const val SPEED_FACTOR = 0.5
    /** Warm tracer colour for rounds without a synced tracer colour (shells, rockets, missiles). */
    private val DEFAULT_RGB = floatArrayOf(1.0f, 0.62f, 0.3f)

    /** Cosmetic tracer velocity for a round reflected at [reflected] blocks per tick. */
    @JvmStatic
    fun tracerVelocity(reflected: Vec3): Vec3 {
        val v = reflected.scale(SPEED_FACTOR)
        val speed = v.length()
        return if (speed > RicochetTracerMessage.MAX_SPEED) v.scale(RicochetTracerMessage.MAX_SPEED / speed) else v
    }

    @JvmStatic
    fun send(projectile: Projectile, from: Vec3, reflected: Vec3) {
        val level = projectile.level() as? ServerLevel ?: return
        val rgb = (projectile as? ProjectileEntity)?.tracerRgb() ?: DEFAULT_RGB
        val width = 0.3f * (ProjectileProfiles.resolve(projectile)?.renderScale ?: 1f)
        val gravity = when (projectile) {
            is ProjectileEntity -> projectile.gravityPerTick()
            is FastThrowableProjectile -> projectile.gravityValue
            else -> 0.05f
        }
        RicochetTracerMessage.send(level, from, tracerVelocity(reflected), rgb, width, gravity)
    }
}
