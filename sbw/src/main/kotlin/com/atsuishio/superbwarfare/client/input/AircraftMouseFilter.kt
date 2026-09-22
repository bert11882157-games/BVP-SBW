package com.atsuishio.superbwarfare.client.input

import kotlin.math.abs

/** A convex blend cannot amplify sustained cursor input or a delayed sample. */
internal object AircraftMouseFilter {
    fun advance(previous: Double, target: Double, activity: Double, gain: Double, minimum: Double): Double {
        if (!target.isFinite() || !activity.isFinite()) return 0.0
        if (!previous.isFinite()) return target
        val response = (gain * abs(activity)).coerceIn(minimum, 1.0)
        return previous + response * (target - previous)
    }
}
