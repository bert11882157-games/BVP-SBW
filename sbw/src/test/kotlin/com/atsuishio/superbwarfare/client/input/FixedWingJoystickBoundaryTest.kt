package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import org.joml.Matrix4f
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingJoystickBoundaryTest {
    private val view = Matrix4f()
    private val nose = Vector3d(0.0, 0.0, -1.0)
    private fun target(x: Double, y: Double, z: Double = -1.0): FixedWingPilotIntent {
        val v = Vector3d(x, y, z).normalize()
        return FixedWingPilotIntent.normalized(v.x, v.y, v.z, 0)!!
    }
    private fun projection(w: Int, h: Int) = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), w.toFloat() / h, 0.05f, 2000f)

    @Test fun circularEdgesMapToUnitTravelAcrossAspectRatiosAndGuiScales() {
        for ((w, h) in listOf(1920 to 1080, 960 to 540, 3440 to 1440, 800 to 1200)) {
            val p = projection(w, h)
            val travel = FixedWingJoystickBoundary.halfTravel(w, h)
            assertTrue(travel + 6.5f <= FixedWingJoystickBoundary.halfSize(w, h))
            for ((x, y) in listOf(10.0 to 0.0, -10.0 to 0.0, 0.0 to 10.0, 0.0 to -10.0, 10.0 to 10.0)) {
                val clipped = FixedWingJoystickBoundary.constrain(target(x, y), nose, view, p, w, h)
                val axes = FixedWingJoystickBoundary.axes(clipped, nose, view, p, w, h)!!
                val length = kotlin.math.hypot(x, y)
                assertEquals((x / length).toFloat(), axes.horizontal, 1e-5f)
                assertEquals((-y / length).toFloat(), axes.vertical, 1e-5f)
            }
        }
    }

    @Test fun interiorTargetsAreUnchangedAndNoseIsNeutral() {
        val p = projection(1280, 720)
        val aim = target(0.02, 0.03)
        assertSame(aim, FixedWingJoystickBoundary.constrain(aim, nose, view, p, 1280, 720))
        val neutral = FixedWingJoystickBoundary.axes(target(0.0, 0.0), nose, view, p, 1280, 720)!!
        assertEquals(0f, neutral.horizontal, 0f)
        assertEquals(0f, neutral.vertical, 0f)
    }

    @Test fun boundaryIntentReachesFullControllerDemandBeforeTheScreenEdge() {
        val camera = Matrix4f().rotateY(Math.PI.toFloat())
        val forward = Vector3d(0.0, 0.0, 1.0)
        for (fov in listOf(30.0, 70.0, 110.0)) {
          val p = Matrix4f().perspective(Math.toRadians(fov).toFloat(), 1280f / 720, 0.05f, 2000f)
          for ((x, y) in listOf(10.0 to 0.0, -10.0 to 0.0, 0.0 to 10.0, 0.0 to -10.0)) {
            val h = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingHandlingProfile.GAME_JET
            val model = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightModel(h)
            model.reset(0.0, 0.0, 0.0)
            val controller = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingMouseAimController(h)
            val clipped = FixedWingJoystickBoundary.constrain(target(x, y, 1.0), forward, camera, p, 1280, 720)
            val axes = FixedWingJoystickBoundary.axes(clipped, forward, camera, p, 1280, 720)!!
            assertTrue(controller.update(model, clipped.copy(screenRollInput = axes.horizontal),
                true, false, 0.0, 0.0, 30.0, 1.0))
            val command = if (x != 0.0) controller.aileronCommand else controller.elevatorCommand
            assertEquals(1.0, kotlin.math.abs(command), 1e-6, "edge($x,$y) FOV=$fov must request full authority")
          }
        }
    }

    @Test fun clippingTracksBankedCameraAndIsIdempotent() {
        val p = projection(1280, 720)
        val bank = Matrix4f().rotateZ(0.8f)
        val clipped = FixedWingJoystickBoundary.constrain(target(4.0, 3.0), nose, bank, p, 1280, 720)
        val again = FixedWingJoystickBoundary.constrain(clipped, nose, bank, p, 1280, 720)
        assertEquals(clipped.directionX, again.directionX, 1e-8)
        assertEquals(clipped.directionY, again.directionY, 1e-8)
        assertEquals(clipped.directionZ, again.directionZ, 1e-8)
        val axes = FixedWingJoystickBoundary.axes(clipped, nose, bank, p, 1280, 720)!!
        assertTrue(kotlin.math.abs(axes.horizontal) <= 1f && kotlin.math.abs(axes.vertical) <= 1f)
    }

    @Test fun repeatedOutwardMouseMotionDoesNotAccumulateOutsideBoundaryAndIdleCameraDoesNotSteer() {
        val state = FixedWingMouseAimState()
        val p = projection(1280, 720)
        val basis = FixedWingMouseAimMath.Basis(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0))
        val initial = target(0.0, 0.0)
        fun sample(frame: Long, cursor: Double, camera: FixedWingMouseAimMath.Basis = basis): FixedWingPilotIntent =
            state.sample(frame, frame * 50_000_000, initial, camera, cursor, 0.0, 1.0, false, false) {
                FixedWingJoystickBoundary.constrain(it, nose, view, p, 1280, 720)
            }!!
        sample(0, 0.0)
        var atEdge = initial
        for (i in 1L..30L) atEdge = sample(i, i * 100.0)
        val edge = FixedWingJoystickBoundary.axes(atEdge, nose, view, p, 1280, 720)!!
        assertEquals(1f, kotlin.math.abs(edge.horizontal), 1e-4f)
        val returned = sample(31, 2990.0)
        val inside = FixedWingJoystickBoundary.axes(returned, nose, view, p, 1280, 720)!!
        assertTrue(kotlin.math.abs(inside.horizontal) < 0.99f)
        val movedCamera = FixedWingMouseAimMath.Basis(Vector3d(0.0, 0.0, 1.0), Vector3d(0.0, 1.0, 0.0))
        val settled = sample(50, 2990.0)
        val idle = sample(51, 2990.0, movedCamera)
        assertEquals(settled.directionX, idle.directionX, 1e-5)
        assertEquals(settled.directionZ, idle.directionZ, 1e-5)
    }
}
