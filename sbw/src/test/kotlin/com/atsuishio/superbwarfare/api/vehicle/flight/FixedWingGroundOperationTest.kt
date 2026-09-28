package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingGroundOperationTest {
    private val handling = FixedWingHandlingProfile.GAME_JET

    private fun powered(profile: FixedWingHandlingProfile = handling): FixedWingFlightModel {
        val model = FixedWingFlightModel(profile)
        repeat(50) { assertTrue(model.step(it.toLong(), 0.0, 0.0, 20.0, false, true, throttleAxis = 1.0)) }
        assertEquals(1.0, model.throttle)
        return model
    }

    @Test fun dismountSpoolsDownAndBrakesWithoutResettingThrottle() {
        val model = powered()
        model.resetControls(preserveThrottle = true)
        assertEquals(1.0, model.throttle)
        assertTrue(model.step(51, 0.0, 0.0, 5.0, true, false))
        assertTrue(model.throttle in 0.9..<1.0)
        assertTrue(model.wheelBrakeActive)
        assertTrue(model.velocityZ < 5.0)
        repeat(50) { assertTrue(model.step(52L + it, 0.0, 0.0, 0.0, true, false)) }
        assertEquals(0.0, model.throttle)
        assertEquals(0.0, model.thrustAccelerationMps2)
    }

    @Test fun airbrakeRequiresIdlePowerAndCanCombineWithWheelBrake() {
        val model = powered()
        assertTrue(model.step(51, 0.0, 0.0, 10.0, true, true, airbrakeRequested = true))
        assertEquals(0.0, model.airbrake)
        assertTrue(model.wheelBrakeActive)
        repeat(50) { assertTrue(model.step(52L + it, 0.0, 0.0, 10.0, true, true,
            throttleAxis = -1.0, airbrakeRequested = true)) }
        assertEquals(0.0, model.throttle)
        assertTrue(model.airbrake > 0.0)
        assertTrue(model.wheelBrakeActive)
        val unsupported = FixedWingFlightModel(handling.copy(airbrakeDragPerMetre = 0.0))
        assertTrue(unsupported.step(0, 0.0, 0.0, 10.0, true, true, airbrakeRequested = true))
        assertEquals(0.0, unsupported.airbrake)
        assertTrue(unsupported.wheelBrakeActive)
    }

    @Test fun gearReducesThrustByFifteenPercentAndRetainsSharedSpeedCeiling() {
        val clean = powered()
        val deployed = powered()
        assertTrue(clean.step(51, 0.0, 0.0, 25.0, false, true))
        assertTrue(deployed.step(51, 0.0, 0.0, 25.0, false, true, gearDeployment = 1.0))
        assertEquals(clean.thrustAccelerationMps2 * 0.85, deployed.thrustAccelerationMps2, 1e-10)
        val fastProfile = handling.copy(maximumSpeedMps = 150.0, maximumIndicatedSpeedMps = 150.0)
        assertEquals(650.0 / 3.6, fastProfile.softSpeedLimitMps, 1e-10)
        for (gear in listOf(0.0, 1.0)) {
            val model = FixedWingFlightModel(fastProfile)
            assertTrue(model.step(0, 0.0, -120.0, 70.0, false, false, gearDeployment = gear))
            assertTrue(model.speedMps <= 750.0 / 3.6 + 1e-9)
            val work = model.stepThrustWorkPerKg + model.stepGravityWorkPerKg + model.stepDragWorkPerKg +
                model.stepLiftWorkPerKg + model.stepSideWorkPerKg + model.stepGroundResistanceWorkPerKg
            assertEquals(model.postStepKineticEnergyPerKg - model.preStepKineticEnergyPerKg, work, 1e-8)
        }
    }

    @Test fun taxiSteeringPeaksAtFiveToEightKmhAndNeverTurnsWhileParked() {
        assertEquals(0.0, handling.taxiTurnRateDegreesPerSecond(0.0))
        assertEquals(45.0, handling.taxiTurnRateDegreesPerSecond(5.0 / 3.6), 1e-10)
        assertEquals(45.0, handling.taxiTurnRateDegreesPerSecond(8.0 / 3.6), 1e-10)
        assertTrue(handling.taxiTurnRateDegreesPerSecond(25.0) < 5.0)
    }

    @Test fun manualPitchDoesNotAcquireMouseRollButExplicitRollStillWorks() {
        val model = FixedWingFlightModel(handling)
        val controller = FixedWingMouseAimController(handling)
        for (bank in listOf(0.0, 60.0, 180.0)) {
            model.reset(0.0, 0.0, bank)
            controller.reset()
            val pitch = FixedWingPilotIntent(0.6, 0.0, 0.8, FixedWingPilotIntent.PITCH_UP)
            assertTrue(controller.update(model, pitch, true, false, 0.0, 0.0, 30.0, 1.0))
            assertEquals(1.0, controller.elevatorCommand)
            assertEquals(0.0, controller.aileronCommand)
            val both = FixedWingPilotIntent(0.6, 0.0, 0.8,
                FixedWingPilotIntent.PITCH_UP or FixedWingPilotIntent.ROLL_RIGHT)
            assertTrue(controller.update(model, both, true, false, 0.0, 0.0, 30.0, 1.0))
            assertEquals(1.0, controller.aileronCommand)
        }
    }
}
