package com.atsuishio.superbwarfare.client.flightdisplay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The HUD's pitch, bank and heading read off the engine pose, through a loop and past the vertical. */
class FlightDisplayAttitudeTest {
    private fun near(expected: Double, actual: Double, what: String) {
        val d = abs(((actual - expected) % 360.0 + 540.0) % 360.0 - 180.0)
        assertEquals(0.0, d, 1e-6, "$what: expected $expected, got $actual")
    }

    @Test fun matchesTheOldMappingInNormalFlight() {
        for ((yaw, pitch, roll) in listOf(Triple(0.0, 0.0, 0.0), Triple(37.0, -20.0, 30.0), Triple(-120.0, 45.0, -75.0))) {
            val a = FlightDisplayState.attitude(yaw, pitch, roll)
            near(-pitch, a[0], "pitch up")
            near(roll, a[1], "bank")
            near(yaw, a[2], "heading")
        }
    }

    @Test fun overTheTopOfALoopPitchStaysInRangeAndTheBankTurnsOver() {
        // engine pitch -120 (nose 120 degrees up, past the vertical) is the same as 60 up, inverted, heading reversed
        val a = FlightDisplayState.attitude(10.0, -120.0, 0.0)
        near(60.0, a[0], "pitch up")
        near(180.0, a[1], "bank")
        near(190.0, a[2], "heading")
    }

    @Test fun bodyRatesFromConsecutivePoses() {
        // a 30 deg/s pull in a 90 degree bank: the Euler pitch hardly moves, the body pitch rate is the whole turn
        val a = FlightDisplayState.orientation(0.0, 0.0, 90.0)
        val b = FlightDisplayState.orientation(0.0, 0.0, 90.0).rotateX(Math.toRadians(-3.0))   // 0.1 s of nose-up pull
        val d = org.joml.Quaterniond(a).conjugate().mul(b)
        val pitchUpRate = -d.x * Math.toDegrees(2.0) / 0.1
        assertEquals(30.0, pitchUpRate, 0.05)
        assertEquals(0.0, -d.y * Math.toDegrees(2.0) / 0.1, 1e-6)
    }
}
