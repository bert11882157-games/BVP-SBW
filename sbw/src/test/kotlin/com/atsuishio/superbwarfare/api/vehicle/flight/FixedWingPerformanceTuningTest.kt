package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

/** Owner's speed balance, 2026-09-28: Mach 1 = 400 km/h, shared soft cap 650, hard cap 750, slow transonic gains. */
class FixedWingPerformanceTuningTest {
    @Test fun allHandlingProfilesShareCapsAndJetsFlyOnReferenceThrust() {
        for (h in listOf(FixedWingHandlingProfile.GAME_JET, MiG19FixedWingProfile.HANDLING,
            FixedWingHandlingProfile.GAME_JET.copy(dryAccelerationMps2 = 2.0, maximumSpeedMps = 30.0))) {
            assertEquals(650.0, h.softSpeedLimitMps * 3.6, 1e-9)
            assertEquals(750.0, h.hardSpeedLimitMps * 3.6, 1e-9)
            assertEquals(h.dryAccelerationMps2, h.gameDryAccelerationMps2, 1e-12)
            assertEquals(max(1.0, h.afterburnerMultiplier), h.gameAfterburnerMultiplier, 1e-12)
            assertEquals(0.90, h.gameRollRateDegreesPerSecond / h.rollRateDegreesPerSecond, 1e-12)
        }
        val prop = FixedWingHandlingProfile.GAME_JET.copy(propellerPowerReferenceSpeedMps = 30.0)
        assertEquals(1.6, prop.gameDryAccelerationMps2 / prop.dryAccelerationMps2, 1e-12)
    }

    @Test fun firstSoftCapNeverExceedsTheSharedOne() {
        val h = FixedWingHandlingProfile.GAME_JET
        assertEquals(h.softSpeedLimitMps, h.effectiveSoftSpeedLimitMps, 0.0)
        assertEquals(300.0 / 3.6, h.copy(firstSoftSpeedLimitMps = 300.0 / 3.6).effectiveSoftSpeedLimitMps, 1e-12)
        assertEquals(h.softSpeedLimitMps, h.copy(firstSoftSpeedLimitMps = 700.0 / 3.6).effectiveSoftSpeedLimitMps, 0.0)
        assertEquals(350.0, MiG19FixedWingProfile.HANDLING.effectiveSoftSpeedLimitMps * 3.6, 1e-9)
    }

    @Test fun transonicSurplusShareFallsFromFullToTheFloor() {
        assertEquals(1.0, FixedWingHandlingProfile.transonicSurplusShare(0.5), 0.0)
        assertEquals(1.0, FixedWingHandlingProfile.transonicSurplusShare(0.85), 0.0)
        val mid = FixedWingHandlingProfile.transonicSurplusShare(0.975)
        assertTrue(mid < 1.0 && mid > FixedWingHandlingProfile.TRANSONIC_SURPLUS)
        assertEquals(FixedWingHandlingProfile.TRANSONIC_SURPLUS, FixedWingHandlingProfile.transonicSurplusShare(1.1), 1e-12)
        assertEquals(FixedWingHandlingProfile.TRANSONIC_SURPLUS, FixedWingHandlingProfile.transonicSurplusShare(2.0), 1e-12)
        assertEquals(1.0, FixedWingHandlingProfile.transonicSurplusShare(Double.NaN), 0.0)
    }

    @Test fun jetsGetALowSpeedSurplusBoostThatFadesByThreeHundred() {
        val jet = FixedWingHandlingProfile.GAME_JET.copy(lowSpeedSurplusBoost = FixedWingHandlingProfile.JET_LOW_SPEED_BOOST)
        assertEquals(1.8, jet.lowSpeedSurplusMultiplier(0.0), 1e-12)
        assertEquals(1.8, jet.lowSpeedSurplusMultiplier(150.0), 1e-12)
        assertEquals(1.4, jet.lowSpeedSurplusMultiplier(225.0), 1e-12)
        assertEquals(1.0, jet.lowSpeedSurplusMultiplier(300.0), 1e-12)
        assertEquals(1.0, jet.lowSpeedSurplusMultiplier(600.0), 1e-12)
        assertEquals(1.0, FixedWingHandlingProfile.GAME_JET.lowSpeedSurplusMultiplier(100.0), 0.0)
        assertEquals(1.8, MiG19FixedWingProfile.HANDLING.lowSpeedSurplusBoost, 0.0)
        // a boosted jet out-accelerates the same jet without the boost from 100 km/h, and ends at the same top speed
        fun run(h: FixedWingHandlingProfile, ticks: Int): Double {
            val m = FixedWingFlightModel(h)
            m.reset(0.0, 0.0, 0.0)
            var vz = 100.0 / 3.6
            var vy = 0.0
            repeat(ticks) {
                assertTrue(m.step(it.toLong(), 0.0, vy, vz, false, true, throttleAxis = 1.0))
                vy = m.velocityY; vz = m.velocityZ
            }
            return m.speedMps
        }
        assertTrue(run(jet, 40) > run(FixedWingHandlingProfile.GAME_JET, 40))
    }

    @Test fun theTakeoffRollGetsTheLowSpeedBoostAndNoGearDragPenalty() {
        // owner 2026-09-28: jets were slow on the runway even on afterburner (the boost was flight-only)
        val jet = FixedWingHandlingProfile.GAME_JET.copy(lowSpeedSurplusBoost = FixedWingHandlingProfile.JET_LOW_SPEED_BOOST)
        fun roll(h: FixedWingHandlingProfile): Double {
            val m = FixedWingFlightModel(h)
            m.reset(0.0, 0.0, 0.0)
            var vx = 0.0; var vz = 60.0 / 3.6
            repeat(60) {
                assertTrue(m.step(it.toLong(), vx, 0.0, vz, true, true, throttleAxis = 1.0, gearDeployment = 1.0))
                vx = m.velocityX; vz = m.velocityZ
            }
            return m.speedMps
        }
        val boosted = roll(jet)
        val plain = roll(FixedWingHandlingProfile.GAME_JET)
        assertTrue(boosted > plain + 1.0, "boosted roll $boosted m/s vs plain $plain m/s")
    }

    @Test fun poweredModelUsesReferenceThrustAndASmallTaperedAfterburnerSurge() {
        val model = warmed(FixedWingHandlingProfile.GAME_JET)
        assertTrue(model.step(100, 0.0, 0.0, 24.0, false, true))
        val dry = model.thrustAccelerationMps2
        assertEquals(model.handling.gameDryAccelerationMps2, dry, 1e-9)
        val ab = model.handling.gameAfterburnerMultiplier
        val surge = FixedWingAfterburnerBoost.SURGE
        assertTrue(model.step(101, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * ab * (1.0 + surge), model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(116, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * ab * (1.0 + surge * 0.5), model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(131, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * ab, model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(132, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true, engineAvailability = 0.0))
        assertEquals(0.0, model.thrustAccelerationMps2, 0.0)
        assertFalse(model.afterburnerActive)
    }

    @Test fun rapidAfterburnerTogglesCannotRestartTheSurge() {
        val boost = FixedWingAfterburnerBoost()
        val full = 1.0 + FixedWingAfterburnerBoost.SURGE
        assertEquals(full, boost.update(true, 0), 1e-12)
        assertEquals(1.0, boost.update(false, 5), 0.0)
        // relit within the re-arm window: the surge keeps fading from the first light-up, it never restarts
        assertEquals(1.0 + FixedWingAfterburnerBoost.SURGE * (1.0 - 10.0 / 30.0), boost.update(true, 10), 1e-12)
        assertEquals(1.0, boost.update(false, 40), 0.0)
        assertEquals(1.0, boost.update(true, 41), 0.0)
        boost.update(false, 42)
        // re-arms only after REARM_OFF_TICKS since it was last lit (tick 41)
        assertEquals(1.0, boost.update(true, 41 + FixedWingAfterburnerBoost.REARM_OFF_TICKS - 1), 0.0)
        boost.update(false, 300)
        assertEquals(full, boost.update(true, 300 + FixedWingAfterburnerBoost.REARM_OFF_TICKS), 1e-12)
        boost.reset()
        assertEquals(1.0, boost.update(false, 600), 0.0)
    }

    @Test fun oldReferenceSpeedDoesNotApplyAnEarlierSoftCap() {
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        assertTrue(model.step(1, 0.0, 0.0, 100.0, false, true))
        assertEquals(0.0, model.overspeedDragAccelerationMps2, 0.0)
        assertTrue(model.speedMps > 320.0 / 3.6)
    }

    @Test fun realMigDiveCrossesItsFirstSoftCapAndNeverPassesTheHardCap() {
        val model = warmed(MiG19FixedWingProfile.HANDLING, 70.0)
        var speed = 330.0 / 3.6
        var vy = -sin(70.0 * PI / 180.0) * speed
        var vz = cos(70.0 * PI / 180.0) * speed
        var maximum = speed
        repeat(600) { index ->
            assertTrue(model.step((100 + index).toLong(), model.velocityX, vy, vz,
                false, true, afterburnerRequested = true))
            speed = model.speedMps
            vy = model.velocityY
            vz = model.velocityZ
            maximum = max(maximum, speed)
            assertTrue(speed <= 750.0 / 3.6 + 1e-9)
            assertTrue(model.stepDragWorkPerKg <= 0.0)
        }
        assertTrue(maximum > 350.0 / 3.6, "MiG dive must pass its first soft cap, reached ${maximum * 3.6} km/h")
        println("MiG-19 continuous 30-second dive peak: ${maximum * 3.6} km/h")
    }

    @Test fun diveDragIsLowerButGroundClimbAndAirbrakeRemainProtected() {
        fun sample(pitch: Double, vertical: Double, grounded: Boolean = false, brake: Boolean = false): FixedWingFlightModel {
            val model = FixedWingFlightModel()
            model.reset(0.0, pitch, 0.0)
            repeat(8) { assertTrue(model.step(it.toLong(), 0.0, vertical, 90.0, grounded, true, airbrakeRequested = brake)) }
            return model
        }
        val dive = sample(30.0, -52.0)
        val climb = sample(-30.0, 52.0)
        val ground = sample(30.0, -52.0, grounded = true)
        assertTrue(dive.dragAccelerationMps2 < climb.dragAccelerationMps2 * 0.90)
        assertTrue(dive.dragAccelerationMps2 < ground.dragAccelerationMps2)
        assertTrue(sample(30.0, -52.0, brake = true).dragAccelerationMps2 > dive.dragAccelerationMps2)
    }

    @Test fun hardCapClampsCombinedVelocityIncludingAfterburnerAndDives() {
        for (h in listOf(FixedWingHandlingProfile.GAME_JET, MiG19FixedWingProfile.HANDLING)) {
            val model = warmed(h, 45.0)
            assertTrue(model.step(100, 140.0, -150.0, 160.0, false, true, afterburnerRequested = true))
            assertEquals(750.0 / 3.6, model.speedMps, 1e-9)
        }
    }

    private fun warmed(h: FixedWingHandlingProfile, pitch: Double = 0.0): FixedWingFlightModel {
        val model = FixedWingFlightModel(h)
        model.reset(0.0, pitch, 0.0)
        repeat(80) { assertTrue(model.step(it.toLong(), 0.0, 0.0, 24.0, false, true, throttleAxis = 1.0)) }
        return model
    }
}
