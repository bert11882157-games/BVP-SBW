package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Afterburner light-up push (owner, 2026-09-28): a small but noticeable surge when the afterburner lights, fading
 * over 1.5 s. It only comes back after the afterburner has been off for [REARM_OFF_TICKS], so switching it off and
 * on never beats simply keeping it lit.
 */
internal class FixedWingAfterburnerBoost {
    private var active = false
    private var started = Long.MIN_VALUE
    private var lastActive = Long.MIN_VALUE

    fun reset() { active = false; started = Long.MIN_VALUE; lastActive = Long.MIN_VALUE }

    fun update(nextActive: Boolean, tick: Long): Double {
        if (tick < 0 || (started != Long.MIN_VALUE && tick < started) ||
            (lastActive != Long.MIN_VALUE && tick < lastActive)) {
            reset()
            return 1.0
        }
        if (nextActive && !active && (lastActive == Long.MIN_VALUE || tick - lastActive >= REARM_OFF_TICKS)) {
            started = tick
        }
        active = nextActive
        if (active) lastActive = tick
        if (!active || started == Long.MIN_VALUE) return 1.0
        val remaining = (1.0 - (tick - started) / SURGE_TICKS.toDouble()).coerceIn(0.0, 1.0)
        return 1.0 + SURGE * remaining
    }

    companion object {
        const val SURGE = 0.15
        const val SURGE_TICKS = 30L
        const val REARM_OFF_TICKS = 200L
    }
}
