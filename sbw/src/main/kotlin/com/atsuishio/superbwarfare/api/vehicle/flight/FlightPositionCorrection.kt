package com.atsuishio.superbwarfare.api.vehicle.flight

/** Absolute move packets also carry routine tracker refreshes, not just actual teleports. */
object FlightPositionCorrection {
    fun shouldSnap(distanceSquared: Double, blocksPerTick: Double): Boolean {
        if (!distanceSquared.isFinite() || !blocksPerTick.isFinite()) return true
        // Allow a normal three-tick flight interval, including the fastest configured aircraft.
        // Larger discontinuities still reset immediately instead of flying through intervening terrain.
        val threshold = (8.0 + 3.0 * blocksPerTick.coerceAtLeast(0.0)).coerceAtMost(32.0)
        return distanceSquared > threshold * threshold
    }
}
