package com.atsuishio.superbwarfare.entity.vehicle.base

/** One presentation event per airborne-to-gear support episode; never damage or movement authority. */
internal class AircraftGearImpactGate(private val minimumImpactSpeedBlocksPerTick: Double = 0.01) {
    private var previousTick: Long? = null
    private var lastImpactTick: Long? = null
    private var clearTicks = 0
    private var armed = false

    /** Returns the admitted approach speed in blocks/tick, or zero for quiet support. */
    fun sample(tick: Long, complete: Boolean, gearGroundContact: Boolean, speedBlocksPerTick: Double): Double {
        val previous = previousTick
        if (previous != null && tick <= previous) return 0.0
        previousTick = tick
        if (previous != null && tick != previous + 1L) {
            armed = false
            clearTicks = 0
        }
        if (!complete || !speedBlocksPerTick.isFinite() || speedBlocksPerTick < 0.0) {
            armed = false
            clearTicks = 0
            return 0.0
        }
        if (!gearGroundContact) {
            clearTicks = (clearTicks + 1).coerceAtMost(2)
            if (clearTicks == 2) armed = true
            return 0.0
        }
        clearTicks = 0
        val previousImpact = lastImpactTick
        val emit = armed && speedBlocksPerTick >= minimumImpactSpeedBlocksPerTick &&
            (previousImpact == null || tick - previousImpact >= 8L)
        armed = false
        if (!emit) return 0.0
        lastImpactTick = tick
        return speedBlocksPerTick
    }
}
