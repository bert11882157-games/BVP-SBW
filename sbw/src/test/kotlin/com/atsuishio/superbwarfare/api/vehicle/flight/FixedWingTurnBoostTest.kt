package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingTurnBoostTest {
    @Test fun `airborne loaded turn gains real lift while retaining drag and reference data`() {
        val h = FixedWingHandlingProfile.GAME_JET
        val model = FixedWingFlightModel(h)
        model.reset(0.0, -10.0, 45.0)
        assertTrue(model.step(1, 0.0, 0.0, 60.0, false, false, engineAvailability = 0.0))
        // Default step density is sea-level (1.0).
        val pressure = (model.forwardVelocityMps.pow(2) +
            model.verticalVelocityMps.pow(2)) / h.liftReferenceSpeedMps.pow(2)
        val referenceLoad = (pressure * (h.normalizedLiftSlopePerDegree * model.angleOfAttackDegrees)
            .coerceIn(-1.0, 1.0) * (1.0 - 0.8 * model.stallSeverity))
            .coerceIn(-h.maximumNegativeLoadFactor, h.maximumLoadFactor)
        val actualLoad = model.liftAccelerationMps2 / h.gravityMps2
        assertTrue(referenceLoad > 1.0)
        assertTrue(actualLoad > referenceLoad * 1.04)
        assertTrue(actualLoad <= referenceLoad * 1.12)
        assertTrue(model.stepDragWorkPerKg < 0.0)
        assertEquals(1.232, h.gamePitchRateDegreesPerSecond / h.pitchRateDegreesPerSecond, 1e-12)
        println("Loaded turn reference=$referenceLoad G, delivered=$actualLoad G")
    }

    @Test fun `trim and low load unchanged with modest symmetric assistance above one G`() {
        val h = FixedWingHandlingProfile.GAME_JET
        for (load in listOf(-1.0, -0.5, 0.0, 0.5, 1.0))
            assertEquals(load, h.gameTurnLoadFactor(load), 0.0)
        for (load in listOf(2.0, 4.0, 7.5)) {
            assertTrue(h.gameTurnLoadFactor(load) in load..load * 1.12)
            assertEquals(-h.gameTurnLoadFactor(load), h.gameTurnLoadFactor(-load), 1e-12)
        }
    }
}
