package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FixedWingTakeoffHandlingTest {
    private val base = FixedWingHandlingProfile.GAME_JET
    private val adjusted = base.copy(takeoffHandling = FixedWingTakeoffHandling(18.0, 3.0))

    @Test fun calibrationIsBoundedAndScalesOnceWithReferenceLengths() {
        assertThrows(IllegalArgumentException::class.java) { FixedWingTakeoffHandling(18.0, 4.01) }
        assertThrows(IllegalArgumentException::class.java) { FixedWingTakeoffHandling(Double.NaN, 2.0) }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(takeoffHandling = FixedWingTakeoffHandling(base.trimSpeedMps, 2.0))
        }
        val scaled = FixedWingReferenceHandling.scale(adjusted, 0.25)
        for (speed in listOf(0.0, 10.0, 18.0, 21.0, 24.0, 40.0)) {
            assertEquals(adjusted.lowSpeedThrustMultiplier(speed), scaled.lowSpeedThrustMultiplier(speed * 0.25), 1e-12)
            assertEquals(adjusted.pitchAirflowAuthority(speed * speed, 0.65),
                scaled.pitchAirflowAuthority(speed * speed * 0.0625, 0.65), 1e-12)
        }
    }

    @Test fun parkedSurfacesHaveNoAttitudeAuthorityAndFailedEngineHasNoThrust() {
        val surfaces = object : FixedWingSurfaceInput {
            override val elevatorCommand = 1.0
            override val aileronCommand = 1.0
            override val rudderCommand = 1.0
            override val groundRollAuthority = 1.0
            override val groundYaw = true
        }
        val model = FixedWingFlightModel(adjusted)
        model.reset(0.0, 0.0, 0.0)
        assertTrue(model.step(0, 0.0, 0.0, 0.0, true, true, throttleAxis = 1.0,
            engineAvailability = 0.0, surfaces = surfaces))
        assertEquals(0.0, model.pitchDegrees, 0.0)
        assertEquals(0.0, model.rollDegrees, 0.0)
        assertEquals(0.0, model.yawDegrees, 0.0)
        assertEquals(0.0, model.thrustAccelerationMps2, 0.0)
        assertEquals(0.0, model.stepThrustWorkPerKg, 0.0)
    }

    @Test fun highSpeedFlightRemainsBitIdentical() {
        val normal = FixedWingFlightModel(base)
        val calibrated = FixedWingFlightModel(adjusted)
        normal.reset(0.0, -base.trimAngleDegrees, 0.0)
        calibrated.reset(0.0, -base.trimAngleDegrees, 0.0)
        var vx = 0.0; var vy = 0.0; var vz = 40.0
        repeat(80) { tick ->
            assertTrue(sqrt(vx * vx + vy * vy + vz * vz) > base.trimSpeedMps)
            for (model in listOf(normal, calibrated)) assertTrue(model.step(tick.toLong(), vx, vy, vz,
                false, true, throttleAxis = 1.0, engineAvailability = 1.0))
            assertEquals(normal.velocityX, calibrated.velocityX, 0.0)
            assertEquals(normal.velocityY, calibrated.velocityY, 0.0)
            assertEquals(normal.velocityZ, calibrated.velocityZ, 0.0)
            assertEquals(normal.thrustAccelerationMps2, calibrated.thrustAccelerationMps2, 0.0)
            vx = normal.velocityX; vy = normal.velocityY; vz = normal.velocityZ
        }
    }

    @Test fun lowSpeedGainIsContinuousAndEveryAccelerationRemainsAccounted() {
        for (boundary in listOf(18.0, base.trimSpeedMps)) {
            assertTrue(abs(adjusted.lowSpeedThrustMultiplier(boundary - 1e-7) -
                adjusted.lowSpeedThrustMultiplier(boundary + 1e-7)) < 1e-10)
        }
        val model = FixedWingFlightModel(adjusted)
        model.reset(0.0, 0.0, 0.0)
        var vx = 0.0; var vy = 0.0; var vz = 10.0
        repeat(120) { tick ->
            assertTrue(model.step(tick.toLong(), vx, vy, vz, false, true,
                throttleAxis = if (tick < 70) 1.0 else -1.0, engineAvailability = 1.0))
            val work = model.stepThrustWorkPerKg + model.stepGravityWorkPerKg + model.stepLiftWorkPerKg +
                model.stepDragWorkPerKg + model.stepSideWorkPerKg + model.stepGroundResistanceWorkPerKg
            assertEquals(model.postStepKineticEnergyPerKg - model.preStepKineticEnergyPerKg, work, 1e-6)
            vx = model.velocityX; vy = model.velocityY; vz = model.velocityZ
        }
    }
}
