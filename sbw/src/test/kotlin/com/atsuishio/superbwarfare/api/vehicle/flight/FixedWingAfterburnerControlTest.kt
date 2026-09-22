package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingAfterburnerControlTest {
    private fun engage(control: FixedWingAfterburnerControl) {
        assertFalse(control.update(1.0, 0.0, true))
        assertTrue(control.update(1.0, 1.0, true))
    }

    @Test
    fun holdingUpThroughFullThrottleNeverEngages() {
        val control = FixedWingAfterburnerControl()
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        repeat(100) { tick ->
            val boost = control.update(model.throttle, 1.0, true)
            assertFalse(boost)
            assertTrue(model.step(
                tick.toLong(), 0.0, 0.0, 0.0, true, true,
                throttleAxis = 1.0,
                afterburnerRequested = boost,
            ))
            assertFalse(model.afterburnerActive)
        }
        assertEquals(1.0, model.throttle, 0.0)
        assertFalse(control.update(model.throttle, 0.0, true))
        assertTrue(control.update(model.throttle, 1.0, true))
    }

    @Test
    fun sameTickReleaseAndPressSurviveFinalHeldStateSampling() {
        val control = FixedWingAfterburnerControl()
        assertFalse(control.update(1.0, 1.0, true))
        assertFalse(control.update(1.0, 0.0, true))
        assertTrue(control.update(1.0, 1.0, true))
        repeat(20) { assertTrue(control.update(1.0, 1.0, true)) }
    }

    @Test
    fun neutralAndFurtherUpPressesDoNotToggleBoostOff() {
        val control = FixedWingAfterburnerControl()
        engage(control)
        repeat(10) {
            assertTrue(control.update(1.0, 0.0, true))
            assertTrue(control.update(1.0, 1.0, true))
        }
        assertFalse(control.update(1.0, -1.0, true))
        assertFalse(control.update(1.0, 1.0, true))
        engage(control)
    }

    @Test
    fun everyEligibilityLossRequiresAFreshReleaseAndPress() {
        for (reason in listOf("pilot", "trait", "wreck", "engine", "power", "fuel")) {
            val control = FixedWingAfterburnerControl()
            engage(control)
            assertFalse(control.update(1.0, 1.0, false), reason)
            assertFalse(control.update(1.0, 1.0, true), reason)
            assertFalse(control.update(1.0, 0.0, true), reason)
            assertTrue(control.update(1.0, 1.0, true), reason)
        }
    }

    @Test
    fun lifecycleResetAndPartialThrottleCannotReviveLatch() {
        val control = FixedWingAfterburnerControl()
        engage(control)
        control.reset()
        assertFalse(control.update(1.0, 1.0, true))
        engage(control)
        assertFalse(control.update(0.99, 0.0, true))
        assertFalse(control.update(1.0, 1.0, true))
        engage(control)
    }

    @Test
    fun invalidInputsClearAllPendingAuthority() {
        val invalid = listOf(
            Double.NaN to 0.0,
            Double.POSITIVE_INFINITY to 1.0,
            -0.1 to 0.0,
            1.1 to 0.0,
            1.0 to Double.NaN,
            1.0 to 2.0,
        )
        for ((throttle, axis) in invalid) {
            val control = FixedWingAfterburnerControl()
            engage(control)
            assertFalse(control.update(throttle, axis, true))
            assertFalse(control.update(1.0, 1.0, true))
        }
    }

    @Test
    fun acceptedBoostSurvivesStickRecenterAndIncreasesThrust() {
        val control = FixedWingAfterburnerControl()
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        repeat(40) { tick ->
            model.step(tick.toLong(), 0.0, 0.0, 0.0, true, true, throttleAxis = 1.0)
        }
        assertFalse(control.update(model.throttle, 0.0, true))
        val boost = control.update(model.throttle, 1.0, true)
        assertTrue(model.step(
            40L, 0.0, 0.0, 24.0, false, true,
            throttleAxis = 1.0,
            afterburnerRequested = boost,
            recenterRequested = true,
        ))
        assertTrue(model.afterburnerActive)
        assertEquals(
            model.handling.gameDryAccelerationMps2 * model.handling.gameAfterburnerMultiplier * 1.5,
            model.thrustAccelerationMps2,
            1.0E-9,
        )
        assertEquals(36.0, model.handling.maximumSpeedMps, 0.0)
        assertTrue(control.update(model.throttle, 0.0, true))
    }

    @Test
    fun boostEnergyCostIsStrongerAndCannotOverflow() {
        val profile = FixedWingHandlingProfile.GAME_JET
        assertEquals(profile.operationalEnergyPerTick * 2,
            profile.afterburnerOperationalEnergyPerTick)
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(operationalEnergyPerTick = Int.MAX_VALUE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.copy(afterburnerEnergyMultiplier = 0)
        }
    }
}
