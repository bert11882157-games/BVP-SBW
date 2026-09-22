package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.*
import org.joml.Vector3d
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingWorldAimTest {
    private fun nose(yaw: Double, pitch: Double): Vector3d {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return Vector3d(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }
    private fun intent(v: Vector3d) = FixedWingPilotIntent.normalized(v.x, v.y, v.z, 0)!!

    @Test fun largeGesturesSelectDirectionsBeyondTheFormerCircleAndBehindTheNose() {
        for (fps in listOf(30, 60, 144)) {
            val state = FixedWingMouseAimState()
            val initial = intent(nose(0.0, 0.0))
            val camera = FixedWingMouseAimMath.aircraftBasis(0f, 0f, 0f)
            state.sample(0, 1_000_000_000, initial, camera, 0.0, 0.0, 1.0, false, false)
            var aim = initial
            for (frame in 1..fps) {
                aim = state.sample(frame.toLong(), 1_000_000_000L + frame * 1_000_000_000L / fps,
                    aim, camera, 800.0 * frame / fps, 0.0, 1.0, false, false)!!
            }
            for (frame in 1..fps) {
                aim = state.sample((fps + frame).toLong(), 2_000_000_000L + frame * 1_000_000_000L / fps,
                    aim, camera, 800.0, 0.0, 1.0, false, false)!!
            }
            assertEquals(-0.5, aim.directionZ, 1e-7, "Full 120-degree gesture at $fps FPS")
            assertEquals(0.0, aim.directionY, 1e-7)
        }
    }

    @Test fun keyboardOverridesDoNotCarryTheSelectedWorldDirectionWithTheCamera() {
        val state = FixedWingMouseAimState()
        val initial = intent(nose(20.0, -15.0))
        val camera = FixedWingMouseAimMath.aircraftBasis(0f, 0f, 0f)
        state.sample(0, 1_000_000_000, initial, camera, 0.0, 0.0, 1.0, false, false)
        for (frame in 1..180) {
            val moved = FixedWingMouseAimMath.aircraftBasis(frame.toFloat(), frame * 0.2f, frame.toFloat())
            val aim = state.sample(frame.toLong(), 1_000_000_000L + frame * 20_000_000L,
                initial.copy(manualMask = if (frame < 150) 8 else 0), moved,
                0.0, 0.0, 1.0, false, false)!!
            assertEquals(initial.directionX, aim.directionX, 1e-9)
            assertEquals(initial.directionY, aim.directionY, 1e-9)
            assertEquals(initial.directionZ, aim.directionZ, 1e-9)
            assertEquals(if (frame < 150) 8 else 0, aim.manualMask)
        }
    }

    @Test fun freshMouseGesturesRemainAdmittedAndQualifiedWhileFlightKeysAreHeld() {
        for (mask in listOf(1,2,4,8,15)) {
            val state=FixedWingMouseAimState()
            val camera=FixedWingMouseAimMath.aircraftBasis(0f,0f,0f)
            val initial=intent(nose(0.0,0.0)).copy(manualMask=mask)
            val view=Matrix4f().lookAt(0f,0f,0f,0f,0f,1f,0f,1f,0f)
            val projection=Matrix4f().perspective(Math.toRadians(70.0).toFloat(),16f/9f,0.1f,1000f)
            state.sample(0,1_000_000_000,initial,camera,0.0,0.0,1.0,false,false)
            var aim=initial
            for (frame in 1..8) {
                aim=state.sample(frame.toLong(),1_000_000_000L+frame*50_000_000L,aim,
                    camera,frame*10.0,frame*25.0,1.0,false,false)!!
                assertTrue(state.consumedMouseGesture(frame.toLong()),"held mask=$mask")
                state.inversionRequested(view,projection,0.0,0.0,1.0)
                assertEquals(mask,aim.manualMask)
            }
            assertTrue(aim.directionY < -0.3)
            assertTrue(state.inversionRequested(view,projection,0.0,0.0,1.0))
        }
    }

    @Test fun indicatorUsesTheActualWorldProjectionAndKeepsOffscreenTargetsVisible() {
        val width = 1280; val height = 720
        val view = Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)
        val projection = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), width.toFloat()/height, 0.1f, 1000f)
        val ray = nose(35.0, 0.0)
        val marker = FixedWingMouseAimMath.project(ray.x, ray.y, ray.z, view, projection, width, height)!!
        assertTrue(marker.onScreen)
        assertTrue(abs(marker.x - width/2f) > min(width, height)*0.3f, "Marker travels beyond old circle")
        for (yaw in listOf(80.0, 120.0, 180.0, -120.0)) {
            val target = nose(yaw, 0.0)
            val edge = FixedWingMouseAimMath.project(target.x, target.y, target.z, view, projection, width, height)!!
            assertFalse(edge.onScreen)
            assertTrue(edge.x in 10f..(width-11f) && edge.y in 10f..(height-11f))
            assertTrue(FixedWingMouseAimMath.screenRollError(intent(target), 0.0, 0.0, 1.0,
                view, projection)!!.isFinite())
        }
    }

    @Test fun idleDirectionSurvivesBodyAndCameraMotionAcrossFrameRates() {
        for (fps in listOf(30, 60, 144)) {
            val state = FixedWingMouseAimState()
            val initial = intent(nose(0.0, 0.0))
            val basis = FixedWingMouseAimMath.aircraftBasis(0f, 0f, 0f)
            state.sample(0, 1_000_000_000, initial, basis, 0.0, 0.0, 1.0, false, false)
            var aim = state.sample(1, 1_050_000_000, initial, basis, 60.0, -40.0, 1.0, false, false)!!
            repeat(20) { i ->
                aim = state.sample((i+2).toLong(), 1_100_000_000L+i*50_000_000L,
                    aim, basis, 60.0, -40.0, 1.0, false, false)!!
            }
            val settled = aim
            for (i in 1..fps*20) {
                val camera = FixedWingMouseAimMath.aircraftBasis(i*0.13f, i*0.017f, i*0.021f)
                aim = state.sample((i+22).toLong(), 2_100_000_000L+i*1_000_000_000L/fps,
                    aim, camera, 60.0, -40.0, 1.0, false, false)!!
                assertEquals(settled.directionX, aim.directionX, 1e-9)
                assertEquals(settled.directionY, aim.directionY, 1e-9)
                assertEquals(settled.directionZ, aim.directionZ, 1e-9)
                assertFalse(state.consumedMouseGesture((i+22).toLong()))
            }
        }
    }

    @Test fun upInputUsesScreenUpThroughDivesAndInvertedAttitudes() {
        for (pitch in listOf(-100.0, -90.0, -80.0, 0.0, 80.0, 90.0, 100.0)) {
            val camera = FixedWingMouseAimMath.aircraftBasis(15f, pitch.toFloat(), 0f)
            val forward = nose(15.0, pitch)
            val frame = FixedWingMouseAimMath.steeringBasis(forward, camera)!!
            for (bank in listOf(-180.0, -90.0, 0.0, 90.0, 180.0)) {
                val state = FixedWingMouseAimState()
                val initial = intent(forward)
                state.sample(0, 1_000_000_000, initial, camera, 0.0, 0.0, 1.0, false, false)
                val aim = state.sample(1, 1_050_000_000, initial, camera, 0.0, -40.0, 1.0, false, false) {
                    FixedWingJoystickBoundary.constrainHeld(it, frame, 1280, 720)
                }!!
                val axes = FixedWingJoystickBoundary.heldAxes(aim, frame, 1280, 720)!!
                assertEquals(0f, axes.horizontal, 1e-6f)
                assertTrue(axes.vertical < -0.1f, "pitch=$pitch bank=$bank")
                // Aircraft bank affects surface allocation, never the selected world destination.
                val model = FixedWingFlightModel(FixedWingHandlingProfile.GAME_JET)
                model.reset(15.0, pitch, bank)
                val controller = FixedWingMouseAimController(FixedWingHandlingProfile.GAME_JET)
                assertTrue(controller.update(model, aim.copy(screenRollInput = 0f), true, false,
                    forward.x*40, forward.y*40, forward.z*40, 1.0))
                assertTrue(controller.elevatorCommand.isFinite() && controller.aileronCommand.isFinite())
                if (bank == 0.0) assertTrue(controller.elevatorCommand > 0.05,
                    "Upright pull-up lost elevator at pitch=$pitch")
            }
        }
    }

    @Test fun circleAndAuthorityIgnoreCameraChaseMagnitudeAndAircraftBank() {
        val forward = nose(30.0, 70.0)
        for (yawOffset in listOf(-70f, 0f, 70f)) {
            val camera = FixedWingMouseAimMath.aircraftBasis(30f+yawOffset, 55f, 0f)
            val frame = FixedWingMouseAimMath.steeringBasis(forward, camera)!!
            assertTrue(FixedWingMouseAimMath.validBasis(frame))
            for (angle in 0..315 step 45) {
                val radians = Math.toRadians(angle.toDouble())
                val ray = Vector3d(forward).fma(cos(radians)*5, frame.right)
                    .fma(sin(radians)*5, frame.up).normalize()
                val clipped = FixedWingJoystickBoundary.constrainHeld(intent(ray), frame, 1280, 720)
                val axes = FixedWingJoystickBoundary.heldAxes(clipped, frame, 1280, 720)!!
                assertEquals(1f, hypot(axes.horizontal, axes.vertical), 1e-5f)
                assertEquals(cos(radians).toFloat(), axes.horizontal, 1e-5f)
            }
        }
    }
}
