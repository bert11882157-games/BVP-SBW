package com.atsuishio.superbwarfare.api.vehicle.flight

import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.acos

class FlightAttitudeContinuityTest {
    @Test fun sustainedHeadingWrapAndDelayedSamplesStayOnThePhysicalShortArc() {
        var yaw = 179F
        var received = yaw
        for (tick in 0 until 72000) {
            val previous = yaw
            yaw = VehicleFlightAttitude.wrap(yaw + if (tick % 1200 < 600) 3F else -3F)
            val aligned = VehicleFlightAttitude.alignedPrevious(previous, yaw)
            assertEquals(3F, abs(yaw - aligned), 1e-5F)
            assertTrue(yaw >= -180F && yaw < 180F)
            // Six delayed server samples coalesce; no accumulated yaw winding reaches rendering.
            if (tick % 7 == 0) {
                val a = VehicleFlightAttitude.quaternion(received, 15F, -30F)
                val b = VehicleFlightAttitude.quaternion(yaw, 15F, -30F)
                var last = a
                for (frame in 1..12) {
                    val value = rotation(VehicleFlightAttitude.interpolate(received, 15F, -30F,
                        yaw, 15F, -30F, frame / 12F))
                    assertTrue(distance(last, value) < 2.0)
                    last = value
                }
                assertTrue(distance(last, b) < 0.001)
                received = yaw
            }
        }
    }
    @Test fun lifecycleGuardComparesPhysicalAttitudeRatherThanEulerBranches() {
        for (sign in listOf(-1F, 1F)) {
            assertEquals(0.0, VehicleFlightAttitude.separationDegrees(
                0F, sign * 91F, 0F, 180F, sign * 89F, 180F), 0.001)
            assertEquals(2.0, VehicleFlightAttitude.separationDegrees(
                0F, 0F, sign * 179F, 0F, 0F, sign * -179F), 0.001)
        }
        assertTrue(VehicleFlightAttitude.separationDegrees(0F, 0F, 0F, 0F, 0F, 50F) > 45.0)
        assertTrue(VehicleFlightAttitude.separationDegrees(Float.NaN, 0F, 0F, 0F, 0F, 0F) > 45.0)
    }
    private fun distance(a: Quaterniond, b: Quaterniond): Double =
        Math.toDegrees(2.0 * acos(abs(a.dot(b)).coerceIn(0.0, 1.0)))

    private fun rotation(a: VehicleFlightAttitude.Angles) =
        VehicleFlightAttitude.quaternion(a.yaw, a.pitch, a.roll)

    @Test fun invertedRollCrossingKeepsEveryRenderedSampleOnTheShortArc() {
        for (side in listOf(-1F, 1F)) for (yaw in listOf(-170F, 0F, 73F)) {
            val previous = VehicleFlightAttitude.quaternion(yaw, 12F, side * 179F)
            val current = VehicleFlightAttitude.quaternion(yaw, 12F, side * -179F)
            for (frame in 0..33) {
                val partial = frame / 33F
                val actual = rotation(VehicleFlightAttitude.interpolate(yaw, 12F, side * 179F,
                    yaw, 12F, side * -179F, partial))
                assertTrue(distance(previous, actual) <= 2.001, "rendered long arc at $partial")
                assertTrue(distance(actual, Quaterniond(previous).slerp(current, partial.toDouble())) < 0.001)
            }
        }
    }

    @Test fun verticalPitchCrossingPreservesCoupledYawAndRoll() {
        for (pitchSign in listOf(-1F, 1F)) for (heading in listOf(-125F, 0F, 80F)) {
            val previous = VehicleFlightAttitude.quaternion(heading, pitchSign * 89F, 0F)
            val current = VehicleFlightAttitude.quaternion(heading + 180F, pitchSign * 89F, 180F)
            for (frame in 0..32) {
                val partial = frame / 32F
                val actual = VehicleFlightAttitude.interpolate(heading, pitchSign * 89F, 0F,
                    heading + 180F, pitchSign * 89F, 180F, partial)
                assertTrue(actual.yaw.isFinite() && actual.pitch.isFinite() && actual.roll.isFinite())
                assertTrue(distance(rotation(actual), Quaterniond(previous).slerp(current,
                    partial.toDouble())) < 0.001, "pole pose changed at $actual")
            }
        }
    }

    @Test fun canonicalWireAnglesAndEntityHistoryCannotIntroduceAFullTurn() {
        // Mirrors snapshot receipt -> fixed-wing entity application -> partial-tick rendering.
        val surfaces = FixedWingControlSurfaceSnapshot(0, 0F, 0F, 0F, 0F, 0F, false)
        var rawRoll = 170F
        val cache = VehicleFlightAttitude.Cache()
        for (step in 1..250) {
            val next = VehicleFlightAttitude.wrap(170F + step * 4F)
            val older = VehicleFlightInstrumentSnapshot.EMPTY.copy(bodyRoll = rawRoll, controlSurfaces = surfaces)
            val newer = older.copy(sequence = step, serverTick = step.toLong(), bodyRoll = next)
            val applied = VehicleFlightInstrumentSnapshot.interpolate(older, newer, 1F).bodyRoll
            val current = VehicleFlightAttitude.wrap(applied)
            val previous = VehicleFlightAttitude.alignedPrevious(rawRoll, current)
            assertTrue(abs(current - previous) < 4.001F)
            for (frame in 0..8) {
                val sample = cache.sample(0F, 0F, previous, 0F, 0F, current, frame / 8F)
                val expected = VehicleFlightAttitude.quaternion(0F, 0F, previous + frame * 0.5F)
                assertTrue(distance(rotation(sample), expected) < 0.001)
            }
            rawRoll = current
        }
    }

    @Test fun snapshotInterpolationUsesTheSamePhysicalRotationAcrossVerticalFlight() {
        val surfaces = FixedWingControlSurfaceSnapshot(0, 0F, 0F, 0F, 0F, 0F, false)
        val previous = VehicleFlightInstrumentSnapshot.EMPTY.copy(bodyPitch = -89F, controlSurfaces = surfaces)
        val current = previous.copy(sequence = 1, serverTick = 1, bodyYaw = 180F, bodyRoll = 180F)
        val middle = VehicleFlightInstrumentSnapshot.interpolate(previous, current, 0.5F)
        val actual = VehicleFlightAttitude.quaternion(middle.bodyYaw, middle.bodyPitch, middle.bodyRoll)
        assertTrue(distance(actual, VehicleFlightAttitude.quaternion(0F, -90F, 0F)) < 0.001)
    }
}
