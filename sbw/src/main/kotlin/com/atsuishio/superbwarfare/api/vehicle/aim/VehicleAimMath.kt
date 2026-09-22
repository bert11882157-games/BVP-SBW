package com.atsuishio.superbwarfare.api.vehicle.aim

import net.minecraft.util.Mth

internal object VehicleAimMath {
    private const val FULL_YAW_RANGE_DEGREES = 360F
    private const val FULL_YAW_EPSILON_DEGREES = 1.0E-4F

    data class DirectionAngles(
        val yaw: Float,
        val pitch: Float,
    )

    fun applyYawRange(yaw: Float, minYaw: Float, maxYaw: Float): Float =
        if (isFullYawRange(minYaw, maxYaw)) Mth.wrapDegrees(yaw) else Mth.clamp(yaw, minYaw, maxYaw)

    fun previousYawForInterpolation(
        previousYaw: Float,
        nextYaw: Float,
        minYaw: Float,
        maxYaw: Float,
    ): Float = if (isFullYawRange(minYaw, maxYaw)) {
        nextYaw - Mth.wrapDegrees(nextYaw - previousYaw)
    } else {
        previousYaw
    }

    fun softenDeltaNearRange(
        current: Float,
        delta: Float,
        minimum: Float,
        maximum: Float,
        softZone: Float,
    ): Float {
        if (softZone <= 0F || delta == 0F || isFullYawRange(minimum, maximum)) return delta
        val distance = if (delta > 0F) maximum - current else current - minimum
        if (distance >= softZone) return delta
        if (distance <= 0F) return 0F
        val t = Mth.clamp(distance / softZone, 0F, 1F)
        return delta * (t * t * (3F - 2F * t))
    }

    fun isFullYawRange(minYaw: Float, maxYaw: Float): Boolean =
        maxYaw - minYaw >= FULL_YAW_RANGE_DEGREES - FULL_YAW_EPSILON_DEGREES

    fun directionAngles(x: Double, y: Double, z: Double): DirectionAngles {
        val horizontal = kotlin.math.sqrt(x * x + z * z)
        return DirectionAngles(
            Math.toDegrees(kotlin.math.atan2(x, z)).toFloat(),
            -Math.toDegrees(kotlin.math.atan2(y, horizontal)).toFloat(),
        )
    }

    fun isNewerSequence(candidate: Int, previous: Int): Boolean =
        (candidate - previous).let { delta ->
            delta != 0 && Integer.compareUnsigned(delta, Int.MIN_VALUE) < 0
        }
}
