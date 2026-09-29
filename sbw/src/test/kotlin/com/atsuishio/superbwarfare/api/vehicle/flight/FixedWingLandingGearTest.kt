package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Owner 2026-09-29: the gear only moves when the pilot commands it. */
class FixedWingLandingGearTest {
    private val g = FixedWingLandingGear

    @Test fun touchdownWithGearUpDoesNotLowerIt() {
        var fraction = 1F
        repeat(40) { fraction = g.nextFraction(fraction, retract = true, onGround = true) }
        assertEquals(1F, fraction, "retracted gear stays up on the ground")
    }

    @Test fun gearTravelsOnlyOnCommand() {
        var fraction = 0F
        repeat(20) { fraction = g.nextFraction(fraction, retract = true, onGround = false) }
        assertEquals(1F, fraction)
        repeat(20) { fraction = g.nextFraction(fraction, retract = false, onGround = false) }
        assertEquals(0F, fraction)
        // weight on wheels: a retraction under way pauses, it never reverses by itself
        assertEquals(0.5F, g.nextFraction(0.5F, retract = true, onGround = true))
    }

    @Test fun togglesLowerAnytimeButRaiseOnlyAirborne() {
        assertTrue(g.canToggle(0F, onGround = false, gearUp = false), "raise in the air")
        assertFalse(g.canToggle(0F, onGround = true, gearUp = false), "no raising on the ground")
        assertTrue(g.canToggle(1F, onGround = true, gearUp = true), "belly-landed aircraft can lower its gear")
        assertTrue(g.canToggle(0.4F, onGround = false, gearUp = true), "lowering mid-travel")
        assertFalse(g.canToggle(0.4F, onGround = false, gearUp = false), "raise only from fully down")
        assertFalse(g.canToggle(Float.NaN, onGround = false, gearUp = true))
    }
}
