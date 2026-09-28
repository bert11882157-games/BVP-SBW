package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonicCrossingTest {
    @Test fun addonSpeedIsNotOurCrossingAndThresholdChatterDoesNotRepeat() {
        val detector = FixedWingSonicCrossing()
        assertFalse(detector.update(69.0 * 3.6, 0))
        assertFalse(detector.update(70.0 * 3.6, 1))
        // Mach 1 is 400 km/h in the game (owner, 2026-09-28); the boom re-arms below 380
        assertFalse(detector.update(399.999, 2))
        assertTrue(detector.update(400.0, 3))
        assertFalse(detector.update(399.0, 104))
        assertFalse(detector.update(401.0, 105))
        assertFalse(detector.update(380.0, 106))
        assertTrue(detector.update(401.0, 107))
    }

    @Test fun spawnAboveBarrierAndQuickRearmDoNotBoom() {
        val detector = FixedWingSonicCrossing()
        assertFalse(detector.update(450.0, 0))
        assertFalse(detector.update(470.0, 1))
        assertFalse(detector.update(370.0, 2))
        assertTrue(detector.update(450.0, 3))
        assertFalse(detector.update(370.0, 4))
        assertFalse(detector.update(450.0, 5))
    }
}
