package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftWreckFlightTest {
    @Test fun retainedFuselageHasTwoShallowBouncesThenKeepsSlidingMomentum() {
        var velocity = Vec3(3.0,-4.0,1.0)
        repeat(2) { bounce ->
            val next=AircraftFuselageWreck.bounce(velocity,bounce)
            assertTrue(next.y in .08.. .26)
            assertTrue(next.horizontalDistance() < velocity.horizontalDistance())
            velocity=Vec3(next.x,-next.y,next.z)
        }
        val slide=AircraftFuselageWreck.bounce(velocity,2)
        assertEquals(0.0,slide.y)
        assertTrue(slide.horizontalDistance()>.5)
        assertTrue(slide.horizontalDistance()<velocity.horizontalDistance())
    }
    @Test fun delayedWingLossIsBoundedSingleSidedAndDoesNotRestartAfterImpact() {
        for (seed in 0L..1000L) {
            val id = java.util.UUID(seed, seed * 193)
            val delay = AircraftWreckBreakup.delayTicks(id)
            assertTrue(delay in 40..200)
            if (AircraftWreckBreakup.mask(id) != 0) continue
            assertEquals(0, AircraftWreckBreakup.timedMask(id, delay - 1L, false))
            assertTrue(AircraftWreckBreakup.timedMask(id, delay.toLong(), false) in 1..2)
            assertEquals(0, AircraftWreckBreakup.timedMask(id, delay + 20L, true))
        }
    }
    @Test fun survivingWingLossIgnoresPilotButKeepsAircraftHealthOutsideFlightOwnership() {
        val live = input().copy(wreck = false)
        val a = AircraftWreckFlightStrategy.step(live, 40, 1, false, detachedWings = 1)
        val b = AircraftWreckFlightStrategy.step(live.copy(pitchInput = 0.0, rollInput = 0.0,
            throttleInput = 0.0, occupied = false), 40, 1, false, detachedWings = 1)
        assertEquals(a, b)
        assertTrue(a.motion.horizontalDistance() > 2.0)
    }
    @Test fun wingLossDistributionAndServerRollAgree() {
        val distribution = (0..99).map(AircraftWreckBreakup::outcome).groupingBy { it }.eachCount()
        assertEquals(mapOf(1 to 25, 2 to 25, 3 to 20, 0 to 30), distribution)
        val state = input()
        val left = AircraftWreckFlightStrategy.step(state, 45, 1, false, detachedWings = 1)
        val right = AircraftWreckFlightStrategy.step(state, 45, 1, false, detachedWings = 2)
        assertTrue(net.minecraft.util.Mth.wrapDegrees(left.bodyRoll - state.bodyRollDegrees) > 5)
        assertTrue(net.minecraft.util.Mth.wrapDegrees(right.bodyRoll - state.bodyRollDegrees) < -5)
        assertEquals(left.motion, right.motion, "roll cannot erase world-space inertia")
        var both = state
        repeat(120) { tick ->
            val result = AircraftWreckFlightStrategy.step(both, tick, 1, false, detachedWings = 3)
            both = both.copy(previousMotion = result.motion, bodyPitchDegrees = result.bodyPitch.toDouble(),
                bodyRollDegrees = result.bodyRoll.toDouble(), bodyYawDegrees = result.bodyYaw.toDouble())
        }
        assertTrue(both.bodyPitchDegrees > 75)
        assertTrue(both.previousMotion.y < -5)
    }
    @Test fun detachedSideDropsInTheActualHullFrameAndLiveWheelContactRetainsMomentum() {
        for (yaw in listOf(-175.0, 0.0, 90.0)) for (side in 1..2) {
            val state = input().copy(wreck = false, bodyYawDegrees = yaw,
                bodyPitchDegrees = 0.0, bodyRollDegrees = 0.0, onGround = true)
            val result = AircraftWreckFlightStrategy.step(state, 45, 1, false, detachedWings = side)
            val pose = com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot(
                0, 0, yaw.toFloat(), result.bodyPitch, result.bodyRoll, 0f, 0f, 0.0, 0.0, 0.0, null)
            val frame = pose.applyBaseAttitude(org.joml.Matrix4d().rotateY(Math.toRadians(-yaw)))
            val lost = frame.transformPosition(org.joml.Vector3d(if (side == 1) -3.0 else 3.0, 0.0, 0.0))
            val kept = frame.transformPosition(org.joml.Vector3d(if (side == 1) 3.0 else -3.0, 0.0, 0.0))
            assertTrue(lost.y < kept.y, "missing wing must fall in hull coordinates")
            assertTrue(result.motion.horizontalDistance() > 2.0, "first wheel contact must not stop a live crash")
        }
    }
    private fun input(motion: Vec3 = Vec3(1.5, 0.6, 2.0)) = VehicleFlightInputContext(
        0L, 0, 0.0, 0.0, motion, motion, Vec3(0.0, 0.0, 1.0), Vec3(0.0, 1.0, 0.0),
        1.0, true, true, false, 0.08, bodyYawDegrees = 175.0, bodyPitchDegrees = -35.0,
        bodyRollDegrees = 70.0, throttleInput = 1.0, pitchInput = -1.0, rollInput = 1.0)

    @Test fun `wreck coasts before wandering and a prolonged nose down transition`() {
        var state = input()
        val first = AircraftWreckFlightStrategy.step(state, 0, 1, false)
        assertTrue(first.motion.x > 1.4)
        repeat(200) { tick ->
            val result = AircraftWreckFlightStrategy.step(state, tick, 1, false, -35.0, 70.0, 137L)
            assertEquals(0.0, result.thrust)
            assertEquals(0.0, result.throttle)
            assertTrue(result.motionIncludesGravity)
            assertTrue(result.bodyYaw in -180f..180f)
            assertTrue(result.motion.horizontalDistance() < state.previousMotion.horizontalDistance())
            assertEquals(state.previousMotion.y * AircraftWreckFlightStrategy.MOMENTUM_RETENTION - state.gravityPerTick,
                result.motion.y, 1e-10)
            assertTrue(kotlin.math.abs(result.bodyPitch - state.bodyPitchDegrees) <= 1.801)
            assertTrue(kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(result.bodyRoll - state.bodyRollDegrees)) <= 3.001)
            assertTrue(kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(result.bodyYaw - state.bodyYawDegrees)) <= 3.701)
            if (tick <= 30) {
                assertEquals(-35f, result.bodyPitch)
                assertEquals(70f, result.bodyRoll)
                assertEquals(175f, result.bodyYaw)
            }
            if (tick == 95) assertTrue(result.bodyPitch in 0f..25f)
            if (tick == 140) assertTrue(result.bodyPitch in 25f..70f)
            if (tick >= 180) assertEquals(2.5,
                net.minecraft.util.Mth.wrapDegrees(result.bodyRoll - state.bodyRollDegrees), 0.0001)
            state = state.copy(previousMotion = result.motion, bodyYawDegrees = result.bodyYaw.toDouble(),
                bodyPitchDegrees = result.bodyPitch.toDouble(), bodyRollDegrees = result.bodyRoll.toDouble())
        }
        assertTrue(state.previousMotion.y < 0)
        assertTrue(state.previousMotion.horizontalDistance() in 1.8..1.9)
        assertEquals(82.0, state.bodyPitchDegrees, 0.001)
        assertEquals(0.0, AircraftWreckFlightStrategy.noseDownIntensity(12.0))
        assertTrue(AircraftWreckFlightStrategy.noseDownIntensity(45.0) in 0.1..0.9)
        assertEquals(1.0, AircraftWreckFlightStrategy.noseDownIntensity(state.bodyPitchDegrees))
        assertEquals(0.0, AircraftWreckFlightStrategy.noseDownIntensity(-82.0))
    }

    @Test fun `wandering is repeatable per wreck but differs between wrecks`() {
        val sample = input()
        val first = AircraftWreckFlightStrategy.step(sample, 90, 1, false, -35.0, 70.0, 137L)
        assertEquals(first, AircraftWreckFlightStrategy.step(sample, 90, 1, false, -35.0, 70.0, 137L))
        val other = AircraftWreckFlightStrategy.step(sample, 90, 1, false, -35.0, 70.0, 32145L)
        assertNotEquals(first.bodyYaw, other.bodyYaw)
        assertEquals(first.motion, other.motion)
    }

    @Test fun `pilot controls cannot alter the wreck and contact ends spin`() {
        val sample = input()
        val controlled = AircraftWreckFlightStrategy.step(sample, 50, -1, false)
        val neutral = AircraftWreckFlightStrategy.step(sample.copy(throttleInput = 0.0, pitchInput = 0.0,
            rollInput = 0.0, occupied = false), 50, -1, false)
        assertEquals(controlled, neutral)
        assertEquals(controlled.motion, AircraftWreckFlightStrategy.step(sample.copy(bodyPitchDegrees = 82.0,
            bodyRollDegrees = -179.0, bodyYawDegrees = -165.0), 50, -1, false).motion)
        assertEquals(9.80665 / 400.0, AircraftWreckFlightStrategy.gravityPerTick(9.80665, 0.06))
        assertEquals(0.003, AircraftWreckFlightStrategy.gravityPerTick(1.2, 0.06))
        assertEquals(0.02, AircraftWreckFlightStrategy.gravityPerTick(null, 0.02))
        val falling = sample.copy(previousMotion = Vec3(2.0, 0.0, 0.0), gravityPerTick =
            AircraftWreckFlightStrategy.gravityPerTick(1.2, 0.06))
        assertEquals(-0.003, AircraftWreckFlightStrategy.step(falling, 20, 1, false).motion.y)
        for (contact in listOf(sample.copy(onGround = true), sample.copy(inFluid = true))) {
            val result = AircraftWreckFlightStrategy.step(contact, 50, -1, false)
            assertEquals(Vec3.ZERO, result.motion)
            assertEquals(175f, result.bodyYaw)
        }
        assertEquals(Vec3.ZERO, AircraftWreckFlightStrategy.step(sample, 50, -1, true).motion)
    }
}
