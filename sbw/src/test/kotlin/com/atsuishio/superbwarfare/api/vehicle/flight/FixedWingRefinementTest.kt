package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FixedWingRefinementTest {
    private val h = FixedWingHandlingProfile.GAME_JET

    @Test fun buffetRemainsSubtleAndPilotRollDominatesIt() {
        val neutral = object : FixedWingSurfaceInput {
            override val elevatorCommand = 0.0
            override val aileronCommand = 0.0
            override val rudderCommand = 0.0
            override val groundRollAuthority = 1.0
            override val groundYaw = false
        }
        val model = FixedWingFlightModel(h)
        model.reset(0.0, 0.0, 0.0)
        repeat(200) { t ->
            assertTrue(model.step(t.toLong(), 0.0, 0.0, 130.0, false, true, surfaces = neutral))
            assertTrue(abs(model.rollRateDegreesPerSecond) < 0.46)
            assertTrue(abs(model.pitchRateDegreesPerSecond) < 0.19)
            assertTrue(abs(model.yawRateDegreesPerSecond) < 1.0)
        }
        val pilot = object : FixedWingSurfaceInput by neutral { override val aileronCommand = 1.0 }
        repeat(20) { t -> assertTrue(model.step((200 + t).toLong(), 0.0, 0.0, 130.0, false, true, surfaces = pilot)) }
        assertTrue(model.rollRateDegreesPerSecond > 10.0)
    }

    @Test fun launchThrustIsReducedSubstantiallyAndRecoversSmoothly() {
        for (kmh in 0..50) assertEquals(0.35, h.launchThrustMultiplier(kmh / 3.6), 1e-12)
        var previous = 0.35
        for (kmh in 51..70) {
            val next = h.launchThrustMultiplier(kmh / 3.6)
            assertTrue(next >= previous && next - previous < 0.05)
            previous = next
        }
        assertEquals(1.0, previous, 1e-12)
        assertEquals(1.0, h.launchThrustMultiplier(100.0), 1e-12)
    }

    @Test fun nearMaximumBuffetUsesEachProfileAndOverspeedStaysBounded() {
        val slow = h.copy(maximumSpeedMps = 50.0)
        val fast = h.copy(maximumSpeedMps = 90.0)
        assertEquals(0.0, FixedWingSpeedEffects.buffetDegrees(44.0, slow), 0.0)
        assertTrue(FixedWingSpeedEffects.buffetDegrees(48.0, slow) > 0.0)
        assertEquals(0.0, FixedWingSpeedEffects.buffetDegrees(48.0, fast), 0.0)
        assertEquals(0.10, FixedWingSpeedEffects.buffetDegrees(100.0, fast), 1e-12)
        assertTrue(FixedWingSpeedEffects.buffetDegrees(700.0 / 3.6, fast) > 0.20)
        assertEquals(0.45, FixedWingSpeedEffects.buffetDegrees(10000.0, fast), 1e-12)
    }

    @Test fun sonicBoomRequiresCrossingAndRearmWithoutThresholdChatter() {
        val fresh = FixedWingSonicCrossing()
        assertFalse(fresh.update(390.0, 0))
        assertTrue(fresh.update(400.0, 1))
        val gate = FixedWingSonicCrossing()
        assertFalse(gate.update(410.0, 0))
        assertFalse(gate.update(399.0, 1))
        assertFalse(gate.update(401.0, 2))
        assertFalse(gate.update(379.0, 3))
        assertTrue(gate.update(400.0, 4))
        for (tick in 5L..100L) assertFalse(gate.update(if (tick % 2L == 0L) 399.0 else 401.0, tick))
        assertFalse(gate.update(370.0, 105))
        assertTrue(gate.update(400.0, 106))
    }

    @Test fun smallDownwardAimUsesElevatorAndSmallSidewaysAimDoesNotCommandFullRoll() {
        val model = FixedWingFlightModel(h)
        model.reset(0.0, 0.0, 0.0)
        fun controls(x: Double, y: Double, screen: Float): FixedWingMouseAimController {
            val c = FixedWingMouseAimController(h)
            val length = kotlin.math.sqrt(x * x + y * y + 1.0)
            c.update(model, FixedWingPilotIntent(x / length, y / length, 1.0 / length, screenRollInput = screen),
                true, false, 0.0, 0.0, 30.0, 1.0)
            return c
        }
        val down = controls(0.0, -0.10, 0f)
        assertTrue(down.elevatorCommand < -0.05)
        assertEquals(0.0, down.aileronCommand, 1e-9)
        val fine = controls(0.08, 0.0, 0.15f)
        assertTrue(abs(fine.aileronCommand) in 0.001..0.15)
        val farther = controls(0.30, 0.0, 0.65f)
        assertTrue(abs(farther.aileronCommand) > abs(fine.aileronCommand))
        assertTrue(abs(farther.aileronCommand) in 0.5..1.0,
            "deliberate side travel should now have strong roll authority")
    }
}
