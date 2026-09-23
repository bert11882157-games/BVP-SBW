package com.atsuishio.superbwarfare.api.vehicle.flight

/** Wheel support owns low-speed ground pitch; aerodynamic rotation takes over before takeoff. */
internal object FixedWingGroundAttitude {
    fun settleWeight(speedMps: Double, referenceSpeedMps: Double): Double {
        if (!speedMps.isFinite() || !referenceSpeedMps.isFinite() || referenceSpeedMps <= 0) return 0.0
        val progress = ((speedMps / referenceSpeedMps - 0.35) / 0.30).coerceIn(0.0, 1.0)
        return 1.0 - progress * progress * (3.0 - 2.0 * progress)
    }
}
