package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingImpactModelTest {
    @Test fun wingLossCrashesUseIncomingSpeedBeforeContactErasesIt() {
        val ordinary = FixedWingImpactModel.Damage(.01, false)
        for (side in 1..3) {
            assertTrue(FixedWingImpactModel.afterWingLoss(side, true, true, 0.0, 400.0, ordinary).destructive)
            assertTrue(FixedWingImpactModel.afterWingLoss(side, true, false, .2, 400.0, ordinary).destructive)
            assertEquals(ordinary, FixedWingImpactModel.afterWingLoss(side, true, false, .02, 400.0, ordinary))
            assertEquals(ordinary, FixedWingImpactModel.afterWingLoss(side, true, true, 0.0, 0.0, ordinary))
            assertEquals(ordinary, FixedWingImpactModel.afterWingLoss(side, false, true, .2, 400.0, ordinary))
        }
        assertEquals(ordinary, FixedWingImpactModel.afterWingLoss(0, true, true, .2, 400.0, ordinary))
    }
    @Test fun gentleGearLandingsAreSafeAtEveryForwardSpeed() {
        for (forward in listOf(0.0, 8.0, 25.0, 70.0, 165.0)) {
            for (sink in listOf(0.0, 0.2, 0.5, 1.0, 1.59, 2.0)) {
                val result = FixedWingImpactModel.evaluate(sink * sink, forward * forward + sink * sink, true)
                assertEquals(0.0, result.healthFraction, 0.0)
                assertFalse(result.destructive)
            }
        }
    }

    @Test fun hardLandingDependsOnNormalEnergyAndCannotHideBehindGearState() {
        for (gear in listOf(false, true)) {
            var previous = 0.0
            for (hundredths in 101..599) {
                val normal = hundredths / 100.0
                val result = FixedWingImpactModel.evaluate(normal * normal, normal * normal, gear)
                assertTrue(result.healthFraction >= previous)
                assertFalse(result.destructive)
                previous = result.healthFraction
            }
            for (normal in listOf(6.0, 10.0, 50.0)) {
                val result = FixedWingImpactModel.evaluate(normal * normal, normal * normal + 25.0, gear)
                assertTrue(result.destructive)
                assertEquals(1.0, result.healthFraction, 0.0)
            }
        }
    }

    @Test fun wingAndBellyScrapesDamageButStationaryContactDoesNot() {
        assertEquals(0.0, FixedWingImpactModel.evaluate(0.0, 0.0, false).healthFraction, 0.0)
        for (forward in listOf(2.0, 10.0, 40.0, 100.0)) {
            val scrape = FixedWingImpactModel.evaluate(0.0, forward * forward, false)
            assertTrue(scrape.healthFraction > 0.0 && scrape.healthFraction <= 0.25)
            assertFalse(scrape.destructive)
            assertEquals(0.0, FixedWingImpactModel.evaluate(0.0, forward * forward, true).healthFraction, 0.0)
        }
    }

    @Test fun invalidVelocityCannotReachDamageMutation() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) { FixedWingImpactModel.evaluate(value, 100.0, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { FixedWingImpactModel.evaluate(4.0, 1.0, true) }
    }
}
