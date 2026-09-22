package com.atsuishio.superbwarfare.api.vehicle.flight

/** One-second engagement surge, with a two-second rearm interval to prevent toggle pumping. */
internal class FixedWingAfterburnerBoost {
    private var active = false
    private var started = Long.MIN_VALUE

    fun reset() { active = false; started = Long.MIN_VALUE }

    fun update(nextActive: Boolean, tick: Long): Double {
        if (tick < 0 || (started != Long.MIN_VALUE && tick < started)) {
            reset()
            return 1.0
        }
        if (nextActive && !active && (started == Long.MIN_VALUE || tick - started >= 40L)) {
            started = tick
        }
        active = nextActive
        if (!active || started == Long.MIN_VALUE) return 1.0
        val remaining = (1.0 - (tick - started) / 20.0).coerceIn(0.0, 1.0)
        return 1.0 + 0.5 * remaining
    }
}
