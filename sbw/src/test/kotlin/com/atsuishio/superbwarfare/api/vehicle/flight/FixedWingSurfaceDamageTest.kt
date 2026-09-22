package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingSurfaceDamageTest {
    @Test fun `production model applies damage after pitch loading limits and disables active yaw`() {
        fun step(damage: FixedWingSurfaceDamage, command: Double): FixedWingFlightModel {
            val model = FixedWingFlightModel()
            model.reset(0.0, 0.0, 0.0)
            val input = object : FixedWingSurfaceInput {
                override val elevatorCommand = command
                override val aileronCommand = 0.0
                override val rudderCommand = command
                override val groundRollAuthority = 1.0
                override val groundYaw = false
            }
            assertTrue(model.step(1, 0.0, 0.0, 80.0, false, true, surfaces = input, surfaceDamage = damage))
            return model
        }
        val intact = step(FixedWingSurfaceDamage.INTACT, 1.0)
        val lost = step(FixedWingSurfaceDamage(leftElevator = true, rightElevator = true, rudder = true), 1.0)
        assertTrue(kotlin.math.abs(intact.pitchRateDegreesPerSecond) > 0.0)
        assertEquals(intact.pitchRateDegreesPerSecond * 0.1, lost.pitchRateDegreesPerSecond, 1e-9)
        assertEquals(0.0, lost.yawRateDegreesPerSecond, 1e-9)
        assertTrue(step(FixedWingSurfaceDamage(leftWing = true), 0.0).rollRateDegreesPerSecond < 0.0)
        assertTrue(step(FixedWingSurfaceDamage(rightWing = true), 0.0).rollRateDegreesPerSecond > 0.0)
    }
    @Test fun `independent surfaces preserve limited recovery authority and opposing bias cancels`() {
        assertEquals(1.0, FixedWingSurfaceDamage.INTACT.pitchAuthority)
        assertEquals(0.55, FixedWingSurfaceDamage(leftElevator = true).pitchAuthority)
        assertEquals(0.1, FixedWingSurfaceDamage(leftElevator = true, rightElevator = true).pitchAuthority)
        assertEquals(0.5, FixedWingSurfaceDamage(leftWing = true, rightWing = true).rollAuthority)
        assertEquals(-1.5, FixedWingSurfaceDamage(leftWing = true).rollBiasDegreesPerSecond)
        assertEquals(1.5, FixedWingSurfaceDamage(rightWing = true).rollBiasDegreesPerSecond)
        assertEquals(0.0, FixedWingSurfaceDamage(leftWing = true, rightWing = true).rollBiasDegreesPerSecond)
        assertEquals(0.0, FixedWingSurfaceDamage(rudder = true).yawAuthority)
    }
}
