package com.atsuishio.superbwarfare.client.renderer

import org.joml.Matrix4d
import org.joml.Vector3d
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FixedWingRiderPoseTest {
    @Test fun turningGroundStationKeepsRiderOnItsOwnMovingFrame() {
        val seat = Vector3d(.55, -.6, -.8)
        for (turretYaw in listOf(-170.0, -45.0, 0.0, 90.0)) {
            val hull = Matrix4d().translation(12.0, 3.0, -18.0)
                .rotateY(.7).rotateX(.12).rotateZ(-.08)
            val frame = Matrix4d(hull).translate(.2, 1.4, -.6)
                .rotateY(Math.toRadians(turretYaw))
            val staleRider = hull.transformPosition(seat, Vector3d())
            val expected = frame.transformPosition(seat, Vector3d())
            val corrected = Vector3d(staleRider).add(FixedWingRiderPose.translation(frame, seat, staleRider))
            assertEquals(0.0, corrected.distance(expected), 1e-10)
            val expectedForward = frame.transformDirection(Vector3d(0.0, 0.0, -1.0))
            val renderedForward = FixedWingRiderPose.rotation(frame, 0f).transform(Vector3f(0f, 0f, 1f))
            assertEquals(expectedForward.x, renderedForward.x.toDouble(), 2e-7)
            assertEquals(expectedForward.y, renderedForward.y.toDouble(), 2e-7)
            assertEquals(expectedForward.z, renderedForward.z.toDouble(), 2e-7)
        }
    }

    @Test fun renderedSeatUsesCurrentAircraftFrameAtEveryAttitudeAndSpeed() {
        val seat = Vector3d(-0.05, 2.0, 4.4)
        for (pitch in listOf(-89.0, -45.0, 0.0, 90.0))
        for (roll in listOf(-180.0, -90.0, 0.0, 75.0, 180.0))
        for (speed in listOf(0.0, 50.0, 140.0)) {
            val frame = Matrix4d().translation(100.0 + speed * .025, 120.0, -50.0)
                .rotateY(.7).rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll))
            val rider = Vector3d(100.0, 122.0, -45.6)
            val offset = FixedWingRiderPose.translation(frame, seat, rider)
            val expected = frame.transformPosition(seat, Vector3d())
            assertEquals(0.0, rider.add(offset).distance(expected), 1e-10)
            val rotation = FixedWingRiderPose.rotation(frame, 0f)
            val renderedUp = rotation.transform(Vector3f(0f, 1f, 0f))
            val aircraftUp = frame.transformDirection(Vector3d(0.0, 1.0, 0.0))
            assertEquals(aircraftUp.x, renderedUp.x.toDouble(), 2e-7)
            assertEquals(aircraftUp.y, renderedUp.y.toDouble(), 2e-7)
            assertEquals(aircraftUp.z, renderedUp.z.toDouble(), 2e-7)
        }
    }
}
