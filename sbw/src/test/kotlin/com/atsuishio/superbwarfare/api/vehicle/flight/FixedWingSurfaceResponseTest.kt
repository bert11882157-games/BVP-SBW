package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingSurfaceResponseTest {
    private class Command(var value: Double) : FixedWingSurfaceInput {
        override val elevatorCommand get() = value
        override val aileronCommand get() = value
        override val rudderCommand get() = value
        override val groundRollAuthority = 1.0
        override val groundYaw = false
    }

    @Test fun requestsAndReversalsSlewActualSurfacesAtAircraftSpecificRates() {
        for (response in listOf(2.0, 4.0, 8.0, 12.0)) {
            val h = FixedWingHandlingProfile.GAME_JET.copy(angularResponsePerSecond = response)
            val m = FixedWingFlightModel(h)
            m.reset(0.0, 0.0, 0.0)
            val command = Command(1.0)
            for (tick in 0 until 100) {
                command.value = if (tick < 40) 1.0 else -1.0
                val old = listOf(m.elevator, m.aileron, m.rudder)
                assertTrue(m.step(tick.toLong(), 0.0, 0.0, 0.0, true, true,
                    engineAvailability = 0.0, surfaces = command))
                val actual = listOf(m.elevator, m.aileron, m.rudder)
                val rates = listOf(h.elevatorTravelPerSecond, h.aileronTravelPerSecond, h.rudderTravelPerSecond)
                for (axis in actual.indices) {
                    assertTrue(abs(actual[axis] - old[axis]) <= rates[axis] * 0.05 + 1e-12)
                    assertTrue(actual[axis] in -1.0..1.0)
                }
                if (tick == 0) assertTrue(actual.all { it > 0.0 && it < 1.0 })
                if (tick == 39) assertTrue(actual.all { abs(it - 1.0) < 1e-12 })
                if (tick == 99) assertTrue(actual.all { abs(it + 1.0) < 1e-12 })
                // Deflection on a parked aircraft must not create aerodynamic attitude authority.
                assertEquals(0.0, m.pitchDegrees, 0.0)
                assertEquals(0.0, m.rollDegrees, 0.0)
            }
        }
    }

    @Test fun releasingControlsAndAirbrakeHasFiniteTravel() {
        val h = FixedWingHandlingProfile.GAME_JET
        val m = FixedWingFlightModel(h)
        repeat(30) { t -> assertTrue(m.step(t.toLong(), 0.0, 0.0, 0.0, true, true,
            airbrakeRequested = true, surfaces = Command(1.0))) }
        assertEquals(1.0, m.airbrake, 1e-12)
        assertTrue(m.step(30, 0.0, 0.0, 0.0, true, false, surfaces = Command(1.0)))
        assertTrue(m.elevator in 0.01..0.99)
        assertTrue(m.airbrake in 0.01..0.99)
        repeat(30) { t -> assertTrue(m.step((31 + t).toLong(), 0.0, 0.0, 0.0, true, false)) }
        assertEquals(0.0, m.elevator, 1e-12)
        assertEquals(0.0, m.airbrake, 1e-12)
    }

    @Test fun idleResistanceIncreasesContinuouslyWithoutThrustOrEnergyCreation() {
        for (propeller in listOf(0.0, 12.0)) {
            val h = FixedWingHandlingProfile.GAME_JET.copy(propellerPowerReferenceSpeedMps = propeller,
                dryAccelerationMps2 = 0.0)
            val losses = mutableListOf<Double>()
            for (throttle in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                val m = FixedWingFlightModel(h)
                // Spool while stationary, then compare exactly the same incoming flight state.
                var tick = 0L
                while (m.throttle + 1e-12 < throttle) {
                    assertTrue(m.step(tick++, 0.0, 0.0, 0.0, true, true, throttleAxis = 1.0,
                        engineAvailability = 0.0))
                }
                // Keep the operational engine available while suppressing thrust in this fixture;
                // an unavailable engine correctly has identical idle drag at every throttle.
                assertTrue(m.step(tick, 0.0, 0.0, h.trimSpeedMps, false, true, engineAvailability = 1.0))
                assertEquals(0.0, m.thrustAccelerationMps2, 0.0)
                assertTrue(m.stepDragWorkPerKg < 0.0)
                val work = m.stepThrustWorkPerKg + m.stepGravityWorkPerKg + m.stepDragWorkPerKg +
                    m.stepLiftWorkPerKg + m.stepSideWorkPerKg + m.stepGroundResistanceWorkPerKg
                assertEquals(m.postStepKineticEnergyPerKg - m.preStepKineticEnergyPerKg, work, 1e-8)
                losses += -m.stepDragWorkPerKg
            }
            assertTrue(losses.zipWithNext().all { (a, b) -> a > b })
        }
    }

    @Test fun idleDiveStillConvertsHeightIntoSpeed() {
        val h = FixedWingHandlingProfile.GAME_JET
        val m = FixedWingFlightModel(h)
        m.reset(0.0, 60.0, 0.0)
        val speed = 15.0
        assertTrue(m.step(0, 0.0, -speed * sqrt(3.0) / 2.0, speed / 2.0, false, true))
        assertTrue(m.stepGravityWorkPerKg > 0.0)
        assertTrue(m.postStepKineticEnergyPerKg > m.preStepKineticEnergyPerKg)
        assertEquals(0.0, m.stepThrustWorkPerKg, 0.0)
    }
}
