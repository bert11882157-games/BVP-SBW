package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Server-owned throttle edge policy. update may observe several accepted inputs between
 * simulation ticks; repeating the same held input is idempotent.
 */
class FixedWingAfterburnerControl {
    private var upHeld = false
    private var releasedAtFull = false
    private var latched = false

    fun reset() {
        upHeld = false
        releasedAtFull = false
        latched = false
    }

    fun update(throttle: Double, throttleAxis: Double, eligible: Boolean): Boolean {
        if (!throttle.isFinite() || throttle !in 0.0..1.0 ||
            !throttleAxis.isFinite() || throttleAxis !in -1.0..1.0
        ) {
            reset()
            return false
        }
        val up = throttleAxis > 0.0
        if (!eligible || throttleAxis < 0.0 || throttle < FULL_THROTTLE) {
            reset()
            upHeld = up
            return false
        }

        if (!up) {
            releasedAtFull = true
        } else if (!upHeld && releasedAtFull) {
            latched = true
            releasedAtFull = false
        }
        upHeld = up
        return latched
    }

    companion object {
        /** Allows round-off only at the fully open throttle endpoint. */
        const val FULL_THROTTLE = 1.0 - 1.0E-9
    }
}
