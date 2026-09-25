package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tail-clearance rotation limit must not cap liftoff lift: the takeoff flap increment gives an airframe held at
 * its ground pitch limit the lift it had at the protection angle, and retracts at higher airspeed.
 */
class FixedWingTakeoffFlapTest {
    private fun liftAt(limit: Double, noseUpDegrees: Double, speed: Double): Double {
        val model = FixedWingFlightModel()
        model.groundPitchLimitDegrees = limit
        model.reset(0.0, -noseUpDegrees, 0.0)
        assertTrue(model.step(0L, 0.0, 0.0, speed, true, false, gearDeployment = 1.0))
        return model.liftAccelerationMps2
    }

    @Test
    fun limitedRotationKeepsLiftoffLiftAndFlapsRetractWithSpeed() {
        val model = FixedWingFlightModel()
        model.groundPitchLimitDegrees = 5.5
        val protection = minOf(FixedWingFlightModel().takeoffFlapIncidenceDegrees + 18.0, 18.0)
        assertTrue(model.takeoffFlapIncidenceDegrees > 0.0)
        assertEquals(0.0, FixedWingFlightModel().apply { groundPitchLimitDegrees = 18.0 }.takeoffFlapIncidenceDegrees)

        val reference = 14.0
        val limited = liftAt(5.5, 5.5, reference)
        val unlimited = liftAt(18.0, 5.5 + model.takeoffFlapIncidenceDegrees, reference)
        assertTrue(limited > 0.0 && protection > 0.0)
        assertEquals(unlimited, limited, unlimited * 0.03) { "limited=$limited unlimited=$unlimited" }

        assertEquals(1.0, model.takeoffFlapFraction(reference * reference), 1e-9)
        assertEquals(0.0, model.takeoffFlapFraction(square(2.5 * reference)), 1e-9)
    }

    private fun square(value: Double) = value * value
}
