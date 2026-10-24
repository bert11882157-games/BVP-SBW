package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftLoadoutAccessTest {
    @Test fun stoppedAircraftIsAtOrBelowOneMetrePerSecond() {
        assertFalse(AircraftLoadoutAccess.inMotion(0.0))
        assertFalse(AircraftLoadoutAccess.inMotion(AircraftLoadoutAccess.MAX_STATIONARY_SPEED_SQR))
        assertTrue(AircraftLoadoutAccess.inMotion(Math.nextUp(AircraftLoadoutAccess.MAX_STATIONARY_SPEED_SQR)))
    }

    @Test fun nonFiniteSpeedCountsAsMoving() {
        assertTrue(AircraftLoadoutAccess.inMotion(Double.NaN))
        assertTrue(AircraftLoadoutAccess.inMotion(Double.POSITIVE_INFINITY))
    }

    @Test fun messageIsTheRequestedWording() {
        assertEquals("Pylon menu inaccessible while vehicle is in motion", AircraftLoadoutAccess.IN_MOTION_MESSAGE)
    }
}
