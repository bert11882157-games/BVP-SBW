package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos

class AircraftWarheadPolicyTest {
    @Test fun `five G converts block per tick speed to metres per second before bounding body turn`() {
        val policy = GuidedManeuverPolicy(5.0, 30.0, 8.0)
        for (speed in listOf(2.0, 5.0, 10.0)) {
            assertEquals(Math.toDegrees(5 * 9.80665 / (speed * 20)), policy.turnRate(speed), 1e-10)
            val inherited = Vec3(1.0, -0.2, 0.4)
            val old = Vec3(0.0, 0.0, speed)
            val moved = GuidedMissileGuidance.steer(old.add(inherited), inherited, Vec3(1.0, 1.0, 0.0), policy.turnRate(speed)).subtract(inherited)
            assertEquals(speed, moved.length(), 1e-9)
            val acceleration = acos(old.normalize().dot(moved.normalize()).coerceIn(-1.0, 1.0)) * 20 * speed * 20
            assertEquals(5 * 9.80665, acceleration, 1e-7)
        }
        assertEquals(0.0, policy.turnRate(Double.NaN))
        assertTrue(policy.captures(Vec3(0.0, 0.0, 1.0), Vec3(0.1, 0.0, 1.0)))
        assertFalse(policy.captures(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.0, 0.0)))
    }
    @Test fun `no designation coasts and later captured painting produces a bounded heading change`() {
        val velocity = Vec3(0.0, 0.0, 10.0)
        assertNull(GuidedMissileGuidance.pointDirection(Vec3.ZERO, null))
        val desired = GuidedMissileGuidance.pointDirection(Vec3.ZERO, Vec3(100.0, 0.0, 1000.0))!!
        val policy = GuidedManeuverPolicy(5.0, 30.0, 8.0)
        val seeker = GuidedMissileGuidance.steer(velocity.normalize(), Vec3.ZERO, desired, policy.seekerRateDegrees)
        val result = GuidedMissileGuidance.steer(velocity, Vec3.ZERO, seeker, policy.turnRate(10.0))
        assertTrue(result.x > 0); assertEquals(10.0, result.length(), 1e-9)
        assertTrue(Math.toDegrees(acos(result.normalize().dot(velocity.normalize()))) <= 0.400001)
    }
    @Test fun `damaging ray fan is finite evenly distributed and cannot exceed per-target budget`() {
        val policy = WarheadFragmentPolicy(192, 32.0, 80f, 8)
        var total = Vec3.ZERO
        for (index in 0 until policy.count) {
            val direction = policy.direction(index, 1.7)
            assertEquals(1.0, direction.length(), 1e-10); total = total.add(direction)
        }
        assertTrue(total.length() < 0.2)
        assertEquals(640f, policy.damageFor(192)); assertEquals(0f, policy.damageFor(-1))
        assertThrows(IllegalArgumentException::class.java) { WarheadFragmentPolicy(257, 32.0, 80f, 8) }
        assertThrows(IllegalArgumentException::class.java) { WarheadFragmentPolicy(192, 33.0, 80f, 8) }
    }
}
