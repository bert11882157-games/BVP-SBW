package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos

class GuidedMissileGuidanceTest {
    @Test fun `laser seeker uses a circular 45 degree body cone including its boundary`() {
        val position = Vec3(240.0, 120.0, -600.0)
        val facing = Vec3(1.0, 0.0, 0.0)
        for (axis in listOf(Vec3(0.0, 1.0, 0.0), Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 1.0).normalize())) {
            for (degrees in listOf(0.0, 44.999, 45.0, 45.001, 90.0, 180.0)) {
                val radians = Math.toRadians(degrees)
                val target = position.add(facing.scale(1000 * kotlin.math.cos(radians)))
                    .add(axis.scale(1000 * kotlin.math.sin(radians)))
                val result = GuidedMissileGuidance.laserSeekerDirection(position, facing, target, 4.0, Vec3.ZERO)
                assertEquals(degrees <= 45.0, result != null, "angle=$degrees axis=$axis")
            }
        }
    }

    @Test fun `laser loss coasts and reacquisition does not follow the carrier velocity`() {
        val position = Vec3.ZERO
        val facing = Vec3(0.0, 0.0, 1.0)
        val inherited = Vec3(3.0, 0.0, 0.0)
        assertNull(GuidedMissileGuidance.laserSeekerDirection(position, facing, Vec3(100.0, 0.0, 1.0), 4.0, inherited))
        assertNull(GuidedMissileGuidance.laserSeekerDirection(position, facing, null, 4.0, inherited))
        assertNull(GuidedMissileGuidance.laserSeekerDirection(position, Vec3.ZERO, Vec3(0.0, 0.0, 100.0), 4.0, inherited))
        val visible = Vec3(0.0, 0.0, 100.0)
        assertEquals(GuidedMissileGuidance.laserInterceptDirection(position, visible, 4.0, inherited),
            GuidedMissileGuidance.laserSeekerDirection(position, facing, visible, 4.0, inherited))
    }

    @Test fun speedDependentWobbleCannotAccumulateHeadingOrChangeRelativeSpeed() {
        val inherited = Vec3(1.0, -0.3, 0.4)
        val peaks = mutableListOf<Double>()
        for (speed in listOf(2.0, 4.0, 6.0)) {
            val initial = Vec3(0.0, 0.0, speed)
            var velocity = initial.add(inherited)
            var offset = Vec3.ZERO
            var position = Vec3.ZERO
            var peak = 0.0
            for (age in 1..680) {
                val clean = GuidedMissileGuidance.removeSpinPerturbation(velocity, inherited, offset)
                assertEquals(0.0, clean.subtract(inherited).subtract(initial).length(), 1e-9)
                velocity = GuidedMissileGuidance.spinPerturbation(clean, inherited, age)
                offset = velocity.subtract(clean)
                val relative = velocity.subtract(inherited)
                position = position.add(relative)
                assertEquals(speed, relative.length(), 1e-10)
                peak = maxOf(peak, angle(initial, relative))
                assertTrue(peak < 1.0, "wobble must remain aimable")
                assertTrue(position.horizontalDistance() > 0)
                assertTrue(kotlin.math.abs(position.x) < 0.3 && kotlin.math.abs(position.y) < 0.4,
                    "oscillation must not walk away from the firing line")
            }
            peaks.add(peak)
        }
        assertTrue(peaks[0] > peaks[1] && peaks[1] > peaks[2])
        assertTrue(peaks[0] > 0.7 && peaks[2] < 0.31)
    }
    private val profile = GuidedPropulsionProfile(0.525, 2.1, 0.02625, 60, 24.0, 12)
    private fun angle(a: Vec3, b: Vec3): Double =
        Math.toDegrees(acos(a.normalize().dot(b.normalize()).coerceIn(-1.0, 1.0)))

    @Test
    fun `native fallback ejects at two blocks per tick and accelerates in one second`() {
        val native = GuidedPropulsionProfile.DEFAULT
        assertEquals(2.0, native.launchSpeed())
        assertEquals(6, native.ignitionDelayTicks)
        assertEquals(6.0, native.maxSpeed)
        assertEquals(20, native.thrustDurationTicks)
        assertEquals(4.0, native.speedAfterIgnition(2.0, 10), 1.0e-12)
        assertEquals(6.0, native.speedAfterIgnition(2.0, 20), 1.0e-12)
        assertEquals(24.0, native.maxTurnRateDegreesPerSecond)
    }

    @Test
    fun `ejection is independent of legacy initial speed but bounded by maximum`() {
        val slowInitial = profile.copy(initialSpeed = 0.35, ejectionSpeed = 2.0)
        assertEquals(2.0, slowInitial.launchSpeed())
        assertEquals(1.5, slowInitial.copy(maxSpeed = 1.5).launchSpeed())
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

    @Test fun `tv seeker follows a point anywhere inside its gimbal and drops one behind it`() {
        val position = Vec3.ZERO; val facing = Vec3(0.0, 0.0, 1.0)
        // 60 degrees off the nose: beyond a laser seeker's cone, inside a 75 degree TV gimbal
        val side = Vec3(Math.sin(Math.toRadians(60.0)) * 100.0, 0.0, Math.cos(Math.toRadians(60.0)) * 100.0)
        assertNull(GuidedMissileGuidance.laserSeekerDirection(position, facing, side, 4.0, Vec3.ZERO))
        val steer = GuidedMissileGuidance.tvSeekerDirection(position, facing, side, 4.0, Vec3.ZERO, 75.0)
        assertNotNull(steer); assertTrue(steer!!.x > 0.0)
        assertNull(GuidedMissileGuidance.tvSeekerDirection(position, facing, Vec3(0.0, 0.0, -100.0), 4.0, Vec3.ZERO, 75.0))
        assertNull(GuidedMissileGuidance.tvSeekerDirection(position, facing, null, 4.0, Vec3.ZERO, 75.0))
    }
}
