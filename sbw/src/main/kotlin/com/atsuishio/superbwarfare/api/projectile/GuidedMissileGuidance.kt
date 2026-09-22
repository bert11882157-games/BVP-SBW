package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Stateless guidance math. Propulsion owns speed; guidance owns only the velocity direction. */
object GuidedMissileGuidance {
    const val TICKS_PER_SECOND = 20.0
    private const val MIN_VECTOR_LENGTH_SQUARED = 1.0e-16
    private const val MIN_LOOK_AHEAD_BLOCKS = 2.0

    /**
     * Rotate the actual relative velocity by one bounded 3D angle, not independent Euler clamps.
     * This preserves speed in both thrust and coast, including diagonal and vertical turns.
     */
    @JvmStatic
    fun steer(
        velocity: Vec3,
        inheritedMotion: Vec3,
        targetDirection: Vec3,
        maxTurnRateDegreesPerSecond: Double,
    ): Vec3 {
        if (!finite(velocity) || !finite(inheritedMotion) || !finite(targetDirection) ||
            !maxTurnRateDegreesPerSecond.isFinite() || maxTurnRateDegreesPerSecond <= 0.0
        ) return velocity
        val relative = velocity.subtract(inheritedMotion)
        val speed = relative.length()
        val targetLengthSquared = targetDirection.lengthSqr()
        if (!speed.isFinite() || speed * speed <= MIN_VECTOR_LENGTH_SQUARED ||
            !targetLengthSquared.isFinite() || targetLengthSquared <= MIN_VECTOR_LENGTH_SQUARED
        ) return velocity

        val forward = relative.scale(1.0 / speed)
        val target = targetDirection.normalize()
        val angle = acos(forward.dot(target).coerceIn(-1.0, 1.0))
        val limit = Math.toRadians(maxTurnRateDegreesPerSecond.coerceAtMost(
            GuidedPropulsionProfile.MAX_TURN_RATE_DEGREES_PER_SECOND) / TICKS_PER_SECOND)
        if (angle <= limit) return target.scale(speed).add(inheritedMotion)

        var axis = forward.cross(target)
        if (axis.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED) {
            // An exact reversal has infinitely many valid axes; pick a stable perpendicular.
            axis = forward.cross(if (kotlin.math.abs(forward.y) < 0.9) Vec3(0.0, 1.0, 0.0)
                else Vec3(1.0, 0.0, 0.0))
        }
        axis = axis.normalize()
        val direction = forward.scale(cos(limit)).add(axis.cross(forward).scale(sin(limit))).normalize()
        val result = direction.scale(speed).add(inheritedMotion)
        return if (finite(result)) result else velocity
    }

    /** A time-based look-ahead remains ahead of fast missiles instead of chasing a 1.6-block point. */
    @JvmStatic
    fun targetOnRay(
        position: Vec3,
        rayOrigin: Vec3,
        rayDirection: Vec3,
        relativeSpeed: Double,
        lookAheadTicks: Int,
    ): Vec3? {
        if (!finite(position) || !finite(rayOrigin) || !finite(rayDirection) ||
            !rayDirection.lengthSqr().isFinite() || rayDirection.lengthSqr() <= MIN_VECTOR_LENGTH_SQUARED ||
            !relativeSpeed.isFinite() || relativeSpeed < 0.0 || lookAheadTicks <= 0
        ) return null
        val direction = rayDirection.normalize()
        val ahead = max(MIN_LOOK_AHEAD_BLOCKS,
            relativeSpeed * lookAheadTicks.coerceAtMost(GuidedPropulsionProfile.MAX_GUIDANCE_LOOK_AHEAD_TICKS))
        val along = position.subtract(rayOrigin).dot(direction)
        val target = rayOrigin.add(direction.scale(max(ahead, along + ahead)))
        val steering = target.subtract(position)
        return steering.takeIf { finite(it) && it.lengthSqr() > MIN_VECTOR_LENGTH_SQUARED }
    }

    private fun finite(vector: Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
