package com.atsuishio.superbwarfare.api.vehicle.flight

/** Normalized gear travel: zero is extended, one is fully retracted; one second per stroke. */
object FixedWingLandingGear {
    const val TRAVEL_PER_TICK = 0.05F

    @JvmStatic
    fun canToggle(fraction: Float, onGround: Boolean): Boolean =
        !onGround && fraction.isFinite() && (fraction == 0F || fraction == 1F)

    @JvmStatic
    fun nextFraction(fraction: Float, retract: Boolean, onGround: Boolean): Float {
        if (!fraction.isFinite() || fraction !in 0F..1F) return 0F
        val change = if (retract && !onGround) TRAVEL_PER_TICK else -TRAVEL_PER_TICK
        return (fraction + change).coerceIn(0F, 1F)
    }
}
