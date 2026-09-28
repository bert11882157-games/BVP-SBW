package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Owner direction 2026-09-28: a wing at 25 % loses its aileron (half roll authority), a wing at 0 comes off. */
class FixedWingSurfaceDamageTest {
    private fun step(damage: FixedWingSurfaceDamage, elevator: Double, aileron: Double = 0.0): FixedWingFlightModel {
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        val input = object : FixedWingSurfaceInput {
            override val elevatorCommand = elevator
            override val aileronCommand = aileron
            override val rudderCommand = elevator
            override val groundRollAuthority = 1.0
            override val groundYaw = false
        }
        assertTrue(model.step(1, 0.0, 0.0, 80.0, false, true, surfaces = input, surfaceDamage = damage))
        return model
    }

    @Test fun `elevators and rudder are hull and never lose authority`() {
        val intact = step(FixedWingSurfaceDamage.INTACT, 1.0)
        val wingHurt = step(FixedWingSurfaceDamage(leftAileronDead = true, rightAileronDead = true), 1.0)
        assertEquals(intact.pitchRateDegreesPerSecond, wingHurt.pitchRateDegreesPerSecond, 1e-9)
        assertEquals(intact.yawRateDegreesPerSecond, wingHurt.yawRateDegreesPerSecond, 1e-9)
        assertEquals(1.0, FixedWingSurfaceDamage(leftWingGone = true).pitchAuthority)
        assertEquals(1.0, FixedWingSurfaceDamage(rightWingGone = true).yawAuthority)
    }

    @Test fun `each dead aileron halves roll authority`() {
        assertEquals(1.0, FixedWingSurfaceDamage.INTACT.rollAuthority)
        assertEquals(0.5, FixedWingSurfaceDamage(leftAileronDead = true).rollAuthority)
        assertEquals(0.5, FixedWingSurfaceDamage(rightWingGone = true).rollAuthority)
        assertEquals(0.25, FixedWingSurfaceDamage(leftAileronDead = true, rightAileronDead = true).rollAuthority)
        assertEquals(0.25, FixedWingSurfaceDamage(leftAileronDead = true, leftWingGone = true,
            rightAileronDead = true).rollAuthority)
    }

    @Test fun `a lost wing rolls the aircraft hard towards the missing side`() {
        assertTrue(step(FixedWingSurfaceDamage(leftWingGone = true), 0.0).rollRateDegreesPerSecond < -10.0)
        assertTrue(step(FixedWingSurfaceDamage(rightWingGone = true), 0.0).rollRateDegreesPerSecond > 10.0)
        assertEquals(0.0, FixedWingSurfaceDamage(leftWingGone = true, rightWingGone = true).rollBiasDegreesPerSecond)
        // a dead aileron alone only drifts a little
        assertEquals(-1.5, FixedWingSurfaceDamage(leftAileronDead = true).rollBiasDegreesPerSecond)
    }
}
