package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingPerformanceTuningTest {
    @Test fun allHandlingProfilesShareCapsAndRelativeAccelerationWithoutChangingReferenceValues() {
        for (h in listOf(FixedWingHandlingProfile.GAME_JET, MiG19FixedWingProfile.HANDLING,
            FixedWingHandlingProfile.GAME_JET.copy(dryAccelerationMps2 = 2.0, maximumSpeedMps = 30.0))) {
            assertEquals(400.0, h.softSpeedLimitMps * 3.6, 1e-9)
            assertEquals(500.0, h.hardSpeedLimitMps * 3.6, 1e-9)
            assertEquals(1.6, h.gameDryAccelerationMps2 / (h.dryAccelerationMps2 * 1.12), 1e-12)
            assertEquals(0.90, h.gameRollRateDegreesPerSecond / h.rollRateDegreesPerSecond, 1e-12)
        }
    }

    @Test fun poweredModelUsesAccelerationGainAndTaperedAfterburnerSurge() {
        val model = warmed(FixedWingHandlingProfile.GAME_JET)
        assertTrue(model.step(100, 0.0, 0.0, 24.0, false, true))
        val dry = model.thrustAccelerationMps2
        assertEquals(model.handling.gameDryAccelerationMps2, dry, 1e-9)
        assertTrue(model.step(101, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * model.handling.gameAfterburnerMultiplier * 1.5, model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(111, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * model.handling.gameAfterburnerMultiplier * 1.25, model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(121, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true))
        assertEquals(dry * model.handling.gameAfterburnerMultiplier, model.thrustAccelerationMps2, 1e-9)
        assertTrue(model.step(122, 0.0, 0.0, 24.0, false, true, afterburnerRequested = true, engineAvailability = 0.0))
        assertEquals(0.0, model.thrustAccelerationMps2, 0.0)
        assertFalse(model.afterburnerActive)
    }

    @Test fun rapidAfterburnerTogglesCannotRestartTheSurge() {
        val boost = FixedWingAfterburnerBoost()
        assertEquals(1.5, boost.update(true, 0), 0.0)
        assertEquals(1.0, boost.update(false, 5), 0.0)
        assertEquals(1.25, boost.update(true, 10), 0.0)
        assertEquals(1.0, boost.update(false, 22), 0.0)
        assertEquals(1.0, boost.update(true, 23), 0.0)
        boost.update(false, 39)
        assertEquals(1.5, boost.update(true, 40), 0.0)
        boost.reset()
        assertEquals(1.0, boost.update(false, 41), 0.0)
    }

    @Test fun oldReferenceSpeedDoesNotApplyAnEarlierSoftCap() {
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        assertTrue(model.step(1, 0.0, 0.0, 100.0, false, true))
        assertEquals(0.0, model.overspeedDragAccelerationMps2, 0.0)
        assertTrue(model.speedMps > 320.0 / 3.6)
    }

    @Test fun realMigDiveCanCrossSoftCapAndNeverPassHardCap() {
        val model = warmed(MiG19FixedWingProfile.HANDLING, 70.0)
        var speed = 380.0 / 3.6
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
            assertTrue(speed <= 500.0 / 3.6 + 1e-9)
            assertTrue(model.stepDragWorkPerKg <= 0.0)
        }
        assertTrue(maximum > 400.0 / 3.6, "MiG dive must pass soft cap, reached ${maximum * 3.6} km/h")
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
            assertTrue(model.step(100, 90.0, -100.0, 110.0, false, true, afterburnerRequested = true))
            assertEquals(500.0 / 3.6, model.speedMps, 1e-9)
        }
    }

    private fun warmed(h: FixedWingHandlingProfile, pitch: Double = 0.0): FixedWingFlightModel {
        val model = FixedWingFlightModel(h)
        model.reset(0.0, pitch, 0.0)
        repeat(80) { assertTrue(model.step(it.toLong(), 0.0, 0.0, 24.0, false, true, throttleAxis = 1.0)) }
        return model
    }
}

