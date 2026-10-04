package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.asin

/**
 * Owner 2026-10-02: "tv guided bombs should at least try to glide". A TV bomb flies the server's step
 * (AerialBombEntity: tvGlideDirection -> glideStep -> limitTvSpeed) toward the crosshair on its glide slope instead
 * of dropping away, reaches targets inside its glide range, stays at or under 400 km/h and does not oscillate.
 */
class AircraftTvBombGlideTest {
    private val g = 0.08
    private val walleye = AircraftBombFlight.Glide(5.5, 4.5, 2.5)
    private val kab = AircraftBombFlight.Glide(4.0, 5.0, 2.0)
    private val cap = AircraftBombFlight.TV_SPEED_CAP

    private class Flight(val ticks: Int, val position: Vec3, val velocity: Vec3, val speeds: List<Double>,
                         val paths: List<Double>)

    /** Released level at [speed] from [height] blocks, aiming at ground [range] blocks ahead (null: no aim point). */
    private fun fly(speed: Double, height: Double, range: Double?, glide: AircraftBombFlight.Glide,
                    turnDegrees: Double = 2.0): Flight {
        var position = Vec3(0.0, height, 0.0)
        var velocity = Vec3(speed, 0.0, 0.0)
        val aim = range?.let { Vec3(it, 0.0, 0.0) }
        val speeds = mutableListOf<Double>()
        val paths = mutableListOf<Double>()
        var tick = 0
        while (tick < 4000 && position.y > 0.0) {
            val desired = AircraftBombFlight.tvGlideDirection(position, velocity, aim, glide)
            val previous = velocity.length()
            velocity = AircraftBombFlight.limitTvSpeed(
                AircraftBombFlight.glideStep(velocity, g, desired, Math.toRadians(turnDegrees), glide), previous)
            position = position.add(velocity)
            speeds += velocity.length()
            paths += Math.toDegrees(asin(-velocity.y / velocity.length()))
            tick++
        }
        return Flight(tick, position, velocity, speeds, paths)
    }

    @Test fun `released at an ordinary 400 km per hour it glides about 5 to 1 toward a far aim point`() {
        val far = fly(cap, 300.0, 5000.0, walleye)
        val ratio = far.position.x / 300.0
        assertTrue(ratio in 4.5..7.0, "Walleye glide ratio $ratio")
        // a plain dumb drop from the same release covers far less ground
        var dumb = Vec3(cap, 0.0, 0.0)
        var x = 0.0
        var y = 300.0
        while (y > 0.0) { x += dumb.x; y += dumb.y - g; dumb = dumb.add(0.0, -g, 0.0) }
        assertTrue(far.position.x > 3.0 * x, "glide ${far.position.x} vs dumb drop $x")
        val kabRatio = fly(cap, 300.0, 5000.0, kab).position.x / 300.0
        assertTrue(kabRatio in 3.5..5.5 && kabRatio < ratio, "KAB-500Kr glide ratio $kabRatio")
    }

    @Test fun `it hits an aim point inside its glide range`() {
        for ((height, range) in listOf(300.0 to 1200.0, 500.0 to 900.0, 200.0 to 300.0, 300.0 to 1500.0)) {
            val flight = fly(cap, height, range, walleye)
            assertEquals(range, flight.position.x, 8.0, "release at $height, aim $range ahead")
            assertEquals(0.0, flight.position.z, 1e-6)
        }
    }

    @Test fun `it never flies above 400 km per hour unless released faster, then bleeds down`() {
        val steep = fly(cap, 600.0, 200.0, walleye)
        assertTrue(steep.speeds.all { it <= cap + 1e-9 }, "max ${steep.speeds.max()}")
        val fast = fly(9.0, 300.0, 5000.0, walleye)
        assertTrue(fast.speeds[0] < 9.0)
        assertTrue(fast.speeds[100] <= cap + 0.2, "after 5 s: ${fast.speeds[100]}")
        // the spare energy goes into range: further than a 400 km/h release
        assertTrue(fast.position.x > fly(cap, 300.0, 5000.0, walleye).position.x)
    }

    @Test fun `it holds a steady glide without porpoising`() {
        for (flight in listOf(fly(cap, 400.0, 6000.0, walleye), fly(4.0, 300.0, null, walleye), fly(cap, 400.0, 6000.0, kab))) {
            val settled = flight.paths.drop(80)
            var reversals = 0
            var last = 0.0
            for (i in 1 until settled.size) {
                val d = settled[i] - settled[i - 1]
                if (abs(d) < 0.02) continue
                if (last != 0.0 && d * last < 0) reversals++
                last = d
            }
            assertTrue(reversals <= 1, "path angle reversed $reversals times")
            assertTrue(settled.max() - settled.min() < 12.0, "path ${settled.min()}..${settled.max()}")
            // never climbs
            assertTrue(flight.paths.all { it > -0.5 }, "climbed: ${flight.paths.min()}")
        }
    }

    @Test fun `without an aim point it glides on along its heading`() {
        val free = fly(cap, 300.0, null, walleye)
        assertTrue(free.position.x / 300.0 > 4.0, "free glide ratio ${free.position.x / 300.0}")
    }

    @Test fun `the glide command is level at most and dives straight at a reachable point`() {
        val glide = walleye
        // fast: it may hold level, never climb
        val fastDir = requireNotNull(AircraftBombFlight.tvGlideDirection(Vec3(0.0, 100.0, 0.0), Vec3(8.0, 0.0, 0.0), null, glide))
        assertTrue(fastDir.y <= 1e-9 && fastDir.y > -0.05, "fast ${fastDir.y}")
        // at best glide speed toward a far point: the best glide angle
        val best = AircraftBombFlight.tvGlideDirection(Vec3(0.0, 100.0, 0.0), Vec3(4.5, 0.0, 0.0),
            Vec3(5000.0, 0.0, 0.0), glide)!!
        assertEquals(-1.0 / kotlin.math.sqrt(1 + 5.5 * 5.5), best.y, 1e-9)
        // a point below the glide slope: straight at it
        val point = Vec3(100.0, 0.0, 30.0)
        val straight = AircraftBombFlight.tvGlideDirection(Vec3(0.0, 100.0, 0.0), Vec3(4.5, 0.0, 0.0), point, glide)!!
        val line = point.subtract(Vec3(0.0, 100.0, 0.0)).normalize()
        assertEquals(1.0, straight.dot(line), 1e-9)
    }
}
