package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos

class GuidedMissileGuidanceTest {
    private val profile = GuidedPropulsionProfile(0.525, 2.1, 0.02625, 60, 24.0, 12)
    private fun angle(a: Vec3, b: Vec3): Double =
        Math.toDegrees(acos(a.normalize().dot(b.normalize()).coerceIn(-1.0, 1.0)))

    @Test
    fun `native fallback reaches doubled top speed in one and a half seconds`() {
        val native = GuidedPropulsionProfile.DEFAULT
        assertEquals(1.0, native.initialSpeed)
        assertEquals(4.0, native.maxSpeed)
        assertEquals(30, native.thrustDurationTicks)
        assertEquals(4.0, native.speedAfter(30), 1.0e-12)
        assertEquals(24.0, native.maxTurnRateDegreesPerSecond)
    }

    @Test
    fun `one vector bound covers yaw pitch diagonal and reversal`() {
        val forward = Vec3(0.0, 0.0, 2.1)
        for (target in listOf(Vec3(1.0, 0.0, 0.0), Vec3(0.0, 1.0, 0.0),
            Vec3(1.0, 1.0, 0.0), Vec3(0.0, 0.0, -1.0))) {
            val next = GuidedMissileGuidance.steer(forward, Vec3.ZERO, target, 24.0)
            assertEquals(1.2, angle(forward, next), 1.0e-8)
            assertEquals(forward.length(), next.length(), 1.0e-12)
        }
    }

    @Test
    fun `near target snaps exactly without overshoot or speed loss`() {
        val target = Vec3(0.005, 0.003, 1.0).normalize()
        val next = GuidedMissileGuidance.steer(Vec3(0.0, 0.0, 2.1), Vec3.ZERO, target, 24.0)
        assertTrue(next.normalize().subtract(target).length() < 1.0e-12)
        assertEquals(2.1, next.length(), 1.0e-12)
    }

    @Test
    fun `launcher motion is not turned or added twice`() {
        val inherited = Vec3(0.7, -0.2, 0.1)
        val relative = Vec3(0.0, 0.0, 2.1)
        val target = Vec3(1.0, 1.0, 0.0)
        val fromStationary = GuidedMissileGuidance.steer(relative, Vec3.ZERO, target, 24.0)
        val fromMoving = GuidedMissileGuidance.steer(relative.add(inherited), inherited, target, 24.0)
        assertTrue(fromMoving.subtract(inherited).subtract(fromStationary).length() < 1.0e-12)
    }

    @Test
    fun `look ahead scales with speed and retains camera origin`() {
        val position = Vec3(5.0, 4.0, 100.0)
        val origin = Vec3(1.0, 2.0, 3.0)
        val direction = Vec3(0.0, 0.0, 1.0)
        val slow = GuidedMissileGuidance.targetOnRay(position, origin, direction, 0.5, 12)!!
        val fast = GuidedMissileGuidance.targetOnRay(position, origin, direction, 2.1, 12)!!
        assertEquals(Vec3(-4.0, -2.0, 6.0), slow)
        assertEquals(-4.0, fast.x, 1.0e-12)
        assertEquals(-2.0, fast.y, 1.0e-12)
        assertEquals(25.2, fast.z, 1.0e-12)
    }

    @Test
    fun `invalid or empty input cannot poison motion`() {
        val velocity = Vec3(0.0, 0.0, 2.1)
        for (target in listOf(Vec3.ZERO, Vec3(Double.NaN, 0.0, 1.0), Vec3(1.0e308, 0.0, 0.0))) {
            assertSame(velocity, GuidedMissileGuidance.steer(velocity, Vec3.ZERO, target, 24.0))
        }
        assertSame(velocity, GuidedMissileGuidance.steer(velocity, Vec3.ZERO, Vec3(1.0, 0.0, 0.0), Double.NaN))
        assertNull(GuidedMissileGuidance.targetOnRay(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, 2.1, 12))
    }

    @Test
    fun `flight sequence has bounded acceleration speed and total turning`() {
        var velocity = Vec3(0.0, 0.0, profile.initialSpeed)
        var position = Vec3.ZERO
        var lastSpeed = profile.initialSpeed
        for (tick in 1..120) {
            val speed = profile.speedAfter(tick)
            assertTrue(speed >= lastSpeed && speed <= profile.maxSpeed)
            assertTrue(speed - lastSpeed <= profile.accelerationPerTick + 1.0e-12)
            val beforeTurn = velocity.normalize().scale(speed)
            velocity = GuidedMissileGuidance.steer(beforeTurn, Vec3.ZERO, Vec3(1.0, 1.0, 0.0),
                profile.maxTurnRateDegreesPerSecond)
            assertTrue(angle(beforeTurn, velocity) <= 1.2 + 1.0e-8)
            assertEquals(speed, velocity.length(), 1.0e-10)
            position = position.add(velocity)
            lastSpeed = speed
        }
        assertTrue(position.length() < 252.0)
        assertEquals(profile.maxSpeed, profile.speedAfter(60), 1.0e-12)
        assertEquals(profile.maxSpeed, profile.speedAfter(600), 1.0e-12)
        assertEquals(profile.initialSpeed, profile.speedAfter(-1), 1.0e-12)
    }

    @Test
    fun `flight profile requires bounded complete guidance fields`() {
        assertTrue(profile.isValid())
        assertTrue(GuidedPropulsionProfile.DEFAULT.isValid())
        assertNull(GuidedPropulsionProfile.from(GuidedPropulsionData(0.5, 2.0, 0.025, 60)))
        assertNotNull(GuidedPropulsionProfile.from(GuidedPropulsionData(0.5, 2.0, 0.025, 60, 24.0, 12)))
        assertFalse(profile.copy(maxTurnRateDegreesPerSecond = 0.0).isValid())
        assertFalse(profile.copy(maxTurnRateDegreesPerSecond = Double.NaN).isValid())
        assertFalse(profile.copy(guidanceLookAheadTicks = 0).isValid())
        assertFalse(profile.copy(maxSpeed = Double.POSITIVE_INFINITY).isValid())
    }
}
