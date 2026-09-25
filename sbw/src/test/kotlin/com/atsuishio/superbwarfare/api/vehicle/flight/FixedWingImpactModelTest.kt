package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3
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
            val lethal = if (gear) FixedWingImpactModel.GEAR_LETHAL_SINK_SPEED
                else FixedWingImpactModel.BODY_LETHAL_NORMAL_SPEED
            var previous = 0.0
            for (hundredths in 101 until (lethal * 100).toInt()) {
                val normal = hundredths / 100.0
                val result = FixedWingImpactModel.evaluate(normal * normal, normal * normal, gear)
                assertTrue(result.healthFraction >= previous)
                assertFalse(result.destructive)
                previous = result.healthFraction
            }
            for (normal in listOf(lethal, 10.0, 50.0)) {
                val result = FixedWingImpactModel.evaluate(normal * normal, normal * normal + 25.0, gear)
                assertTrue(result.destructive)
                assertEquals(1.0, result.healthFraction, 0.0)
            }
        }
    }

    @Test fun gearSinkDamageIsZeroForNormalLandingsModerateForHardOnesAndLethalOnlyForCrashes() {
        fun gear(sink: Double) = FixedWingImpactModel.evaluate(sink * sink, sink * sink + 3600.0, true)
        for (sink in listOf(0.5, 1.5, 2.5, 3.0)) assertEquals(0.0, gear(sink).healthFraction, 0.0, "sink=$sink")
        val hard = gear(4.0)
        assertTrue(hard.healthFraction > 0.02 && hard.healthFraction < 0.15, hard.toString())
        assertFalse(hard.destructive)
        val veryHard = gear(8.0)
        assertTrue(veryHard.healthFraction > hard.healthFraction && veryHard.healthFraction <= 0.6)
        assertFalse(veryHard.destructive)
        assertTrue(gear(FixedWingImpactModel.GEAR_LETHAL_SINK_SPEED).destructive)
        // A hull striking the runway at the same 4 m/s is far more damaging than the wheels.
        val belly = FixedWingImpactModel.evaluate(16.0, 16.0 + 3600.0, false)
        assertTrue(belly.healthFraction > hard.healthFraction * 3.0)
    }

    @Test fun gearStrikeIntoAnObstacleFaceScalesWithSpeedAndIsLethalOnlyWhenExtreme() {
        assertEquals(0.0, FixedWingImpactModel.gearStrike(2.0 * 2.0).healthFraction, 0.0)
        val taxi = FixedWingImpactModel.gearStrike(10.0 * 10.0)
        val fast = FixedWingImpactModel.gearStrike(20.0 * 20.0)
        assertTrue(taxi.healthFraction > 0.0 && taxi.healthFraction < fast.healthFraction)
        assertFalse(taxi.destructive || fast.destructive)
        assertTrue(fast.healthFraction <= 0.5)
        assertTrue(FixedWingImpactModel.gearStrike(40.0 * 40.0).destructive)
    }

    @Test fun slowTailOrWingtipScrapeDuringTheRollIsSmall() {
        for (forward in listOf(10.0, 40.0, 80.0)) for (normal in listOf(0.0, 0.5, 1.4)) {
            val total = forward * forward + normal * normal
            val scrape = FixedWingImpactModel.evaluate(normal * normal, total, false, groundRoll = true)
            assertFalse(scrape.destructive)
            assertTrue(scrape.healthFraction > 0.0 && scrape.healthFraction <= 0.02, "$forward $normal $scrape")
            val slide = FixedWingImpactModel.evaluate(normal * normal, total, false)
            assertTrue(slide.healthFraction >= scrape.healthFraction)
        }
        // A real impact while rolling keeps the full hull curve.
        val strike = FixedWingImpactModel.evaluate(16.0, 1616.0, false, groundRoll = true)
        assertEquals(FixedWingImpactModel.evaluate(16.0, 1616.0, false), strike)
        assertTrue(FixedWingImpactModel.evaluate(36.0, 1636.0, false, groundRoll = true).destructive)
    }

    @Test fun wingShearNeedsAMeaningfulNormalImpact() {
        val up = Vec3(0.0, 1.0, 0.0)
        // Rolling at 40 m/s with a wingtip or stabilizer brushing the runway.
        assertFalse(FixedWingImpactModel.shearsWing(Vec3(0.0, -0.012, 2.0), up))
        assertFalse(FixedWingImpactModel.shearsWing(Vec3(0.0, -0.1, 2.0), up))
        assertFalse(FixedWingImpactModel.shearsWing(Vec3(0.1, 0.0, 2.0), Vec3(-1.0, 0.0, 0.0)))
        assertTrue(FixedWingImpactModel.shearsWing(Vec3(0.0, -0.2, 2.0), up))
        assertTrue(FixedWingImpactModel.shearsWing(Vec3(0.0, 0.0, 2.0), Vec3(0.0, 0.0, -1.0)))
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
