package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

/**
 * Owner 2026-10-02: laser guided bombs "lost like all of their ability to turn", and TV munitions "need to turn WAY
 * slower, max turn rate of whatever the missile/bomb is originally based off of".
 */
class AircraftLaserBombGuidanceTest {
    private val g = 0.08
    private val turn = Math.toRadians(2.0)

    /** Flies a laser bomb as AerialBombEntity does; the horizontal miss where it reaches the target's height. */
    private fun drop(altitude: Double, speed: Double, target: Vec3): Double? {
        var position = Vec3(0.0, altitude, 0.0)
        var velocity = Vec3(speed, 0.0, 0.0)
        val glide = AircraftBombFlight.Glide.DEFAULT
        repeat(900) {
            val desired = AircraftBombFlight.guidedDirection(position, target, glide)
            velocity = AircraftBombFlight.glideStep(velocity, g, desired, turn, glide)
            val next = position.add(velocity)
            if (next.y <= target.y) {
                val f = (position.y - target.y) / (position.y - next.y)
                val hit = position.add(next.subtract(position).scale(f))
                return hypot(hit.x - target.x, hit.z - target.z)
            }
            position = next
        }
        return null
    }

    /** Targets inside the glide (no farther than about 2.5 : 1 from the release height). */
    @Test fun `laser bombs released at the usual speeds turn onto the spot`() {
        val cases = listOf(Triple(150.0, 300.0, 0.0), Triple(150.0, 300.0, 100.0), Triple(300.0, 500.0, 0.0),
            Triple(300.0, 500.0, 200.0), Triple(300.0, 900.0, 0.0), Triple(200.0, 450.0, 200.0))
        for (speed in listOf(5.0, 6.0, 8.0)) for ((alt, ahead, side) in cases) {
            val miss = drop(alt, speed, Vec3(ahead, 0.0, side))
            assertTrue(miss != null && miss < 3.0,
                "release ${speed * 72} km/h at $alt m, target $ahead ahead $side aside: miss $miss")
        }
    }

    @Test fun `a laser bomb at release speed has lift to spare for turning`() {
        // 324 km/h: about a degree a tick; from about 400 km/h the fins' own 2 degrees
        val slow = AircraftBombFlight.envelopeTurn(4.5, g, AircraftBombFlight.Glide.DEFAULT)
        val usual = AircraftBombFlight.envelopeTurn(5.5, g, AircraftBombFlight.Glide.DEFAULT)
        assertTrue(Math.toDegrees(slow) in 0.8..1.4, "turn at 324 km/h ${Math.toDegrees(slow)} deg/tick")
        assertTrue(Math.toDegrees(usual) > 1.4, "turn at 396 km/h ${Math.toDegrees(usual)} deg/tick")
    }

    @Test fun `a TV bomb turns no faster than the laser bomb of its class`() {
        for (speed in listOf(3.0, 4.0, 4.5, 5.0, 5.556)) {
            val tv = AircraftBombFlight.tvMaxTurn(speed, g, turn)
            assertTrue(tv <= AircraftBombFlight.envelopeTurn(speed, g, AircraftBombFlight.Glide.DEFAULT) + 1e-12)
            assertTrue(tv <= turn)
        }
        // at its best glide speed (324 km/h) it turns about half as fast as it did (2 degrees a tick)
        val best = Math.toDegrees(AircraftBombFlight.tvMaxTurn(4.5, g, turn))
        assertTrue(best in 0.8..1.4, "TV bomb turn at 324 km/h: $best deg/tick")
        // and its wings do not let it out-turn that: a full-rate command turns it no faster
        val glide = AircraftBombFlight.Glide.TV_DEFAULT
        val start = Vec3(4.5, -0.5, 0.0)
        val wanted = Vec3(0.0, -0.1, 1.0).normalize()
        val capped = AircraftBombFlight.glideStep(start, g, wanted, AircraftBombFlight.tvMaxTurn(start.length(), g, turn), glide)
        val angle = Math.toDegrees(kotlin.math.acos(start.normalize().dot(capped.normalize()).coerceIn(-1.0, 1.0)))
        assertTrue(angle < 1.5, "TV bomb turned $angle deg in a tick")
    }
}
