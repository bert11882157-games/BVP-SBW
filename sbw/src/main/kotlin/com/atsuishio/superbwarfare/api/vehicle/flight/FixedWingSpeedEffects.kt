package com.atsuishio.superbwarfare.api.vehicle.flight

/** Presentation thresholds use game-world km/h, like the speed caps and HUD. */
object FixedWingSpeedEffects {
    fun buffetDegrees(speedMps: Double, handling: FixedWingHandlingProfile): Double {
        if (!speedMps.isFinite()) return 0.0
        val soft = handling.effectiveSoftSpeedLimitMps
        val maximum = minOf(handling.maximumSpeedMps, soft)
        val nearMaximum = smooth((speedMps / maximum - 0.90) / 0.10)
        val overspeed = smooth((speedMps - soft) / maxOf(1.0, handling.hardSpeedLimitMps - soft))
        return 0.10 * nearMaximum + 0.35 * overspeed
    }

    private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
}

/** Crossing-only trigger at Mach 1 (400 km/h): no spawn boom, threshold chatter or rapid rearm. */
internal class FixedWingSonicCrossing {
    private var previous: Double? = null
    private var armed = false
    private var lastBoom = Long.MIN_VALUE
    fun update(speedKmh: Double, tick: Long): Boolean {
        if (!speedKmh.isFinite()) { reset(); return false }
        val old = previous
        previous = speedKmh
        if (old == null) { armed = speedKmh < BOOM_KMH; return false }
        if (speedKmh <= REARM_KMH) armed = true
        if (old >= BOOM_KMH || speedKmh < BOOM_KMH || !armed ||
            (lastBoom != Long.MIN_VALUE && tick - lastBoom < 100)) return false
        armed = false
        lastBoom = tick
        return true
    }
    fun reset() { previous = null; armed = false; lastBoom = Long.MIN_VALUE }

    companion object {
        const val BOOM_KMH = FixedWingHandlingProfile.GAME_MACH_ONE_KMH
        const val REARM_KMH = BOOM_KMH - 20.0
    }
}
