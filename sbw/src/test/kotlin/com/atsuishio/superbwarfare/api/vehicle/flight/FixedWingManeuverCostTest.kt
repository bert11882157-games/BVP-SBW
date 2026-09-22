package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingManeuverCostTest {
    private val profile = FixedWingHandlingProfile.GAME_JET

    @Test fun `actual rolling costs energy symmetrically but steady bank does not`() {
        val rate = profile.gameRollRateDegreesPerSecond
        val full = profile.maneuverDragMps2(60.0, 0.0, rate, false)
        assertTrue(full > 1.0)
        assertEquals(full, profile.maneuverDragMps2(60.0, 0.0, -rate, false))
        assertEquals(full / 4, profile.maneuverDragMps2(60.0, 0.0, rate / 2, false), 1e-9)
        assertEquals(0.0, profile.maneuverDragMps2(60.0, 0.0, 0.0, false))
    }

    @Test fun `climb cost scales with ascent and excludes descent and runway`() {
        val climb = profile.maneuverDragMps2(60.0, 30.0, 0.0, false)
        assertTrue(climb > 1.5)
        assertEquals(climb / 2, profile.maneuverDragMps2(60.0, 15.0, 0.0, false), 1e-9)
        assertEquals(0.0, profile.maneuverDragMps2(60.0, -30.0, 0.0, false))
        assertEquals(0.0, profile.maneuverDragMps2(60.0, 30.0, 90.0, true))
        assertEquals(0.0, profile.maneuverDragMps2(0.0, 0.0, 90.0, false))
    }

    @Test fun `maneuver resistance enters the model drag work without adding energy`() {
        val model = FixedWingFlightModel()
        model.reset(0.0, -30.0, 0.0)
        assertTrue(model.step(serverTick = 1, inputVelocityX = 0.0,
            inputVelocityY = 30.0, inputVelocityZ = 51.961524, grounded = false,
            controlsEnabled = false, engineAvailability = 0.0))
        assertTrue(model.maneuverDragAccelerationMps2 > 1.0)
        assertTrue(model.dragAccelerationMps2 >= model.maneuverDragAccelerationMps2)
        assertTrue(model.stepDragWorkPerKg < 0.0)
        assertTrue(model.stepGravityWorkPerKg < 0.0)
    }
}
