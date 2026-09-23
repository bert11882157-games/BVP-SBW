package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FixedWingGroundAttitudeTest {
    @Test fun pushingDownCannotRaiseTheRearWheelsAtRunwaySpeed() {
        val surfaces = object : FixedWingSurfaceInput {
            override val elevatorCommand = -1.0
            override val aileronCommand = 0.0
            override val rudderCommand = 0.0
            override val groundRollAuthority = 0.0
            override val groundYaw = true
        }
        for (speed in listOf(15.0, 40.0, 80.0)) {
            val model = FixedWingFlightModel()
            model.reset(0.0, 0.0, 0.0)
            repeat(40) { tick ->
                assertTrue(model.step(tick.toLong(), 0.0, 0.0, speed, true, true,
                    surfaces = surfaces, gearDeployment = 1.0))
                assertEquals(0.0, model.pitchDegrees, 1e-8)
            }
        }
    }
    @Test fun lowSpeedSupportResistsPilotPitchButDoesNotPinAirborneOrTakeoffControls() {
        val surfaces = object : FixedWingSurfaceInput {
            override val elevatorCommand = 1.0
            override val aileronCommand = 0.0
            override val rudderCommand = 0.0
            override val groundRollAuthority = 0.0
            override val groundYaw = true
        }
        fun rate(speed: Double, grounded: Boolean): Double {
            val model = FixedWingFlightModel()
            model.reset(0.0, 0.0, 0.0)
            var maximumRate = 0.0
            repeat(20) { tick ->
                assertTrue(model.step(tick.toLong(), 0.0, 0.0, speed, grounded, true,
                    surfaces = surfaces, gearDeployment = 1.0))
                maximumRate = maxOf(maximumRate, abs(model.pitchRateDegreesPerSecond))
            }
            return maximumRate
        }
        assertEquals(0.0, rate(2.0, true), 1e-9)
        assertTrue(rate(40.0, false) > 0.01, "airborne pitch remains available at flying speed")
        assertTrue(rate(40.0, true) > 0.01, "rotation remains available at takeoff speed")
        assertEquals(1.0, FixedWingGroundAttitude.settleWeight(3.0, 20.0))
        assertEquals(0.0, FixedWingGroundAttitude.settleWeight(14.0, 20.0))
    }
}
