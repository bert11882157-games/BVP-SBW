package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Normalized gear travel: zero is extended, one is fully retracted; one second per stroke.
 *
 * The gear moves only when the pilot commands it (owner 2026-09-29: no automatic deployment). Touching the ground
 * no longer lowers it; a weight-on-wheels interlock only stops it from being raised (or from continuing to retract)
 * while the aircraft rests on the ground. Lowering is always allowed, so a belly-landed aircraft can put its gear
 * down again.
 */
object FixedWingLandingGear {
    const val TRAVEL_PER_TICK = 0.05F

    /**
     * A pilot toggle: lowering (the gear is commanded up) is always accepted, even mid-travel or on the ground;
     * raising needs fully extended gear and an airborne aircraft.
     */
    @JvmStatic
    fun canToggle(fraction: Float, onGround: Boolean, gearUp: Boolean): Boolean =
        fraction.isFinite() && (gearUp || (fraction == 0F && !onGround))

    @JvmStatic
    fun nextFraction(fraction: Float, retract: Boolean, onGround: Boolean): Float {
        if (!fraction.isFinite() || fraction !in 0F..1F) return 0F
        val change = when {
            !retract -> -TRAVEL_PER_TICK
            onGround -> 0F          // weight on wheels: hold, never auto-lower
            else -> TRAVEL_PER_TICK
        }
        return (fraction + change).coerceIn(0F, 1F)
    }
}
