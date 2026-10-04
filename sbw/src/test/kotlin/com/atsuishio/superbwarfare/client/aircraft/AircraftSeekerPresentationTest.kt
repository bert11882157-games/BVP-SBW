package com.atsuishio.superbwarfare.client.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AircraftSeekerPresentationTest {
    private val searching = AircraftSeekView(1, "r60", null, null, 0.0, false, "SEARCHING",
        "AIR_TO_AIR", "INFRARED", 25.0, 1024.0, 60, "SECONDARY")

    @Test fun `authoritative receipt owns search acquisition and lock tone including secondary`() {
        val acquiring = searching.copy(target = UUID.randomUUID(), status = "ACQUIRING", progress = 0.5)
        assertEquals(AircraftSeekerPresentation.Tone.SILENT, AircraftSeekerPresentation.tone(searching))
        assertEquals(AircraftSeekerPresentation.Tone.SILENT, AircraftSeekerPresentation.tone(acquiring))
        assertEquals(AircraftSeekerPresentation.Tone.LOCK, AircraftSeekerPresentation.tone(acquiring.copy(ready = true)))
        assertEquals(AircraftSeekerPresentation.Tone.SILENT, AircraftSeekerPresentation.tone(searching.copy(category = "LASER_GUIDED")))
        assertEquals(AircraftSeekerPresentation.Tone.SILENT, AircraftSeekerPresentation.tone(null))
        val gate = AircraftLockConfirmation()
        val locked = acquiring.copy(ready = true)
        assertFalse(gate.update(acquiring, 0))
        assertTrue(gate.update(locked, 60))
        assertFalse(gate.update(locked, 61))
        assertFalse(gate.update(acquiring, 62))
        assertFalse(gate.update(locked, 63))
        assertFalse(gate.update(acquiring, 80))
        assertTrue(gate.update(locked, 92))
        assertFalse(gate.update(locked, 93))
    }

    @Test fun `convergence is frame rate independent and resets when target changes`() {
        val acquiring = searching.copy(target = UUID.randomUUID(), status = "ACQUIRING", progress = 0.7)
        fun simulate(hz: Int): Double {
            val presentation = AircraftSeekerPresentation()
            for (frame in 0..hz) presentation.sample(acquiring, frame.toDouble() / hz)
            assertTrue(presentation.convergence < 1.0)
            return presentation.convergence
        }
        assertEquals(simulate(30), simulate(144), 0.000001)
        val presentation = AircraftSeekerPresentation()
        presentation.sample(acquiring, 0.0)
        presentation.sample(acquiring, 0.1)
        assertTrue(presentation.convergence > 0.0)
        assertEquals(0.0, presentation.sample(acquiring.copy(target = UUID.randomUUID()), 0.2))
        assertEquals(1.0, presentation.sample(acquiring.copy(ready = true), 0.3))
        assertEquals(0.0, presentation.sample(searching, 0.4))
        val geometry = AircraftSeekerPresentation()
        var radius = 100.0
        for (frame in 0..120) {
            val circle = geometry.circle(acquiring, frame / 60.0, 10.0, 20.0, 100.0, Pair(110.0, 60.0))
            assertTrue(circle.radius <= radius + 1e-9)
            assertTrue(circle.x in 10.0..110.0 && circle.y in 20.0..60.0)
            val right = circle.point(0.0)
            val top = circle.point(Math.PI / 2)
            assertEquals(circle.x + circle.radius, right.first, 1e-9)
            assertEquals(circle.y, right.second, 1e-9)
            assertEquals(circle.x, top.first, 1e-9)
            assertEquals(circle.y - circle.radius, top.second, 1e-9)
            radius = circle.radius
        }
        assertTrue(radius in 8.0..35.0)
    }

    @Test fun `a clock step backwards neither restarts acquisition nor snaps the circle`() {
        val acquiring = searching.copy(target = UUID.randomUUID(), status = "ACQUIRING", progress = 0.9)
        val presentation = AircraftSeekerPresentation()
        var last: AircraftSeekerPresentation.Circle? = null
        for (frame in 0..60) last = presentation.circle(acquiring, frame / 60.0, 10.0, 20.0, 100.0, Pair(110.0, 60.0))
        val before = presentation.convergence
        assertTrue(before > 0.5)
        // A dedicated server's time packet corrects the client level clock back by a tick.
        val stepped = presentation.circle(acquiring, 1.0 - 0.05, 10.0, 20.0, 100.0, Pair(110.0, 60.0))
        assertEquals(before, presentation.convergence, 1e-9)
        assertEquals(last!!.radius, stepped.radius, 1e-9)
        assertEquals(last.x, stepped.x, 1e-9)
    }
}
