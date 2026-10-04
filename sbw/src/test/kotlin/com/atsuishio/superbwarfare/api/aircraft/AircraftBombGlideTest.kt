package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.asin

/** Owner 2026-09-30: guided glide bombs bleed energy and can hardly climb. */
class AircraftBombGlideTest {
    private val g = 0.08
    private val glide = AircraftBombFlight.Glide(5.0, 9.0, 2.0)

    private fun fly(start: Vec3, ticks: Int, desired: Vec3?, maxTurnDeg: Double = 2.0): Vec3 {
        var v = start
        repeat(ticks) {
            v = AircraftBombFlight.glideStep(v, g, desired, Math.toRadians(maxTurnDeg), glide)
        }
        return v
    }

    @Test fun `a free glide settles near the glide angle of the lift-to-drag ratio`() {
        // released level at best glide speed, holding its path: it slows, sinks and settles on about 1 : L/D
        val v = fly(Vec3(9.0, 0.0, 0.0), 600, null)
        val path = Math.toDegrees(asin(-v.y / v.length()))
        assertTrue(path in 5.0..25.0, "glide path $path deg")
        assertTrue(v.length() in 4.0..13.0, "glide speed ${v.length()}")
    }

    @Test fun `commanding a climb bleeds speed instead of zooming`() {
        val start = Vec3(10.0, 0.0, 0.0)
        val up = AircraftBombFlight.limitClimb(Vec3(1.0, 1.0, 0.0).normalize())
        assertEquals(AircraftBombFlight.MAX_CLIMB_SINE, up.y, 1e-9)
        var v = start
        var height = 0.0
        repeat(200) {
            v = AircraftBombFlight.glideStep(v, g, up, Math.toRadians(2.0), glide)
            height += v.y
        }
        assertTrue(v.length() < start.length() * 0.8, "speed after trying to climb: ${v.length()}")
        assertTrue(height < 5.0, "height gained: $height")
    }

    @Test fun `turning costs energy and a slow bomb cannot hold its path`() {
        val straight = fly(Vec3(9.0, 0.0, 0.0), 40, Vec3(1.0, -0.2, 0.0).normalize())
        val turning = fly(Vec3(9.0, 0.0, 0.0), 40, Vec3(0.0, -0.2, 1.0).normalize(), 4.0)
        assertTrue(turning.length() < straight.length(), "turn ${turning.length()} vs straight ${straight.length()}")
        // at a third of best glide speed the lift cannot hold the path level: it falls away
        val slow = AircraftBombFlight.glideStep(Vec3(3.0, 0.0, 0.0), g, Vec3(1.0, 0.0, 0.0), 0.1, glide)
        assertTrue(slow.y < -0.05, "slow bomb sinks: ${slow.y}")
    }
}
