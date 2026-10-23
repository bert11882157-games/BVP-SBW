package com.atsuishio.superbwarfare.entity.projectile

import net.minecraft.world.phys.Vec3
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Frame-free nose rotation for SBW's native missiles ([MissileProjectile.turn]). Directions are world-space. */
internal object MissileTurn {
    /** Share of the remaining angle turned per tick, as the native turn always did. */
    const val TURN_FRACTION = 0.95
    /** Below this horizontal length of a unit facing the yaw is left unchanged (within ~0.06 degrees of vertical). */
    const val MIN_HORIZONTAL_FOR_YAW = 1.0e-3

    /**
     * [facing] rotated toward [target] by [TURN_FRACTION] of the angle between them, at most [maxDegrees], about
     * the axis perpendicular to both. Returns a unit vector, or null when either input has no direction or the
     * limit is not positive (no turn).
     */
    fun rotateToward(facing: Vec3, target: Vec3, maxDegrees: Double): Vec3? {
        if (!(maxDegrees > 0.0) || !finite(facing) || !finite(target)) return null
        val facingLength = facing.length()
        val targetLength = target.length()
        if (facingLength < 1.0e-9 || targetLength < 1.0e-9) return null
        val from = facing.scale(1.0 / facingLength)
        val to = target.scale(1.0 / targetLength)
        val angle = acos(from.dot(to).coerceIn(-1.0, 1.0))
        if (angle < 1.0e-9) return to
        val step = minOf(angle * TURN_FRACTION, Math.toRadians(maxDegrees))
        var axis = from.cross(to)
        if (axis.lengthSqr() < 1.0e-18) {
            // Exactly reversed: any perpendicular works; prefer turning through the vertical plane.
            axis = from.cross(if (kotlin.math.abs(from.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0))
        }
        axis = axis.normalize()
        val turned = from.scale(cos(step)).add(axis.cross(from).scale(sin(step))).normalize()
        return if (finite(turned)) turned else null
    }

    private fun finite(v: Vec3): Boolean = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
}
