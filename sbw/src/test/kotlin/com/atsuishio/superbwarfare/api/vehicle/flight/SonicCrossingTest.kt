package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SonicCrossingTest {
    @Test fun addonSpeedIsNotOurCrossingAndThresholdChatterDoesNotRepeat() {
        val detector = FixedWingSonicCrossing()
        assertFalse(detector.update(69.0 * 3.6, 0))
        assertFalse(detector.update(70.0 * 3.6, 1))
        assertFalse(detector.update(349.999, 2))
        assertTrue(detector.update(350.0, 3))
        assertFalse(detector.update(349.0, 104))
        assertFalse(detector.update(351.0, 105))
        assertFalse(detector.update(330.0, 106))
        assertTrue(detector.update(351.0, 107))
    }

    @Test fun spawnAboveBarrierAndQuickRearmDoNotBoom() {
        val detector = FixedWingSonicCrossing()
        assertFalse(detector.update(400.0, 0))
        assertFalse(detector.update(420.0, 1))
        assertFalse(detector.update(320.0, 2))
        assertTrue(detector.update(400.0, 3))
        assertFalse(detector.update(320.0, 4))
        assertFalse(detector.update(400.0, 5))
    }
}
