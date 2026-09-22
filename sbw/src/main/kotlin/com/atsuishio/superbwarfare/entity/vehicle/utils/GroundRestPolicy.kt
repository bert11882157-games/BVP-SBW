package com.atsuishio.superbwarfare.entity.vehicle.utils

import net.minecraft.util.Mth
import kotlin.math.abs

/** Stop numerical settling tails only at rest; driving, falling and recoil remain physical. */
internal object GroundRestPolicy {
    fun isAtRest(onGround: Boolean, inFluid: Boolean, horizontalSpeedSquared: Double,
        hasDriveInput: Boolean): Boolean = onGround && !inFluid && !hasDriveInput &&
        horizontalSpeedSquared.isFinite() && horizontalSpeedSquared <= 2.5E-5

    fun settleAngle(previous: Float, candidate: Float, atRest: Boolean): Float =
        if (atRest && abs(Mth.wrapDegrees(candidate - previous)) <= 0.015F) previous else candidate

    fun settleSteering(rotation: Float, atRest: Boolean): Float =
        if (atRest && abs(rotation) <= 0.015F) 0F else rotation
}
