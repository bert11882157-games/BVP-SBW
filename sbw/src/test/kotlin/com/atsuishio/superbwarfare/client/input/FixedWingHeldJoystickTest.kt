package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightAttitude
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightModel
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingHandlingProfile
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingMouseAimController
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.acos

class FixedWingHeldJoystickTest {
    @Test fun `held indicator keeps its screen direction through aircraft and camera rotations`() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920, 800 to 600)) {
            val state = FixedWingMouseAimState()
            val ray = Vector3d(-0.10, 0.15, 1.0).normalize()
            val start = FixedWingPilotIntent.normalized(ray.x, ray.y, ray.z, 0)!!
            var previousRadius = Double.POSITIVE_INFINITY
            for (frame in 0..240) {
                val body = basis(frame * 3.0, frame * 0.8, frame * 4.0)
                val camera = basis(-frame * 7.0, 30.0, -frame * 2.0)
                val sample = state.sampleHeld(frame.toLong(), 1_000_000_000L + frame * 1_000_000_000L / 60,
                    start, camera, body, 0.0, 0.0, 1.0, false, false)!!
                val axes = FixedWingJoystickBoundary.heldAxes(sample, body, width, height)!!
                val radius = kotlin.math.hypot(axes.horizontal.toDouble(), axes.vertical.toDouble())
                assertEquals(-1.5, axes.vertical / axes.horizontal.toDouble(), 1e-5,
                    "Aircraft/camera motion rotated the marker at frame=$frame")
                assertTrue(radius <= previousRadius + 1e-6)
                previousRadius = radius
            }
        }
    }

    @Test fun `circular travel remains bounded and reversible in every direction and attitude`() {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920))
        for (roll in listOf(-180.0, -90.0, 0.0, 90.0, 180.0))
        for (direction in 0..7) {
            val body = basis(35.0, 89.9, roll)
            val angle = direction * Math.PI / 4.0
            val ray = Vector3d(body.right).mul(kotlin.math.cos(angle))
                .fma(kotlin.math.sin(angle), body.up)
            val target = FixedWingPilotIntent.normalized(ray.x, ray.y, ray.z, 0)!!
            val clamped = FixedWingJoystickBoundary.constrainHeld(target, body, width, height)
            val axes = FixedWingJoystickBoundary.heldAxes(clamped, body, width, height)!!
            assertEquals(kotlin.math.cos(angle), axes.horizontal.toDouble(), 1e-6)
            assertEquals(-kotlin.math.sin(angle), axes.vertical.toDouble(), 1e-6)
            assertTrue(FixedWingMouseAimMath.localDirection(clamped, body).z > 0.0)
            val again = FixedWingJoystickBoundary.constrainHeld(clamped, body, width, height)
            assertEquals(clamped.directionX, again.directionX, 1e-10)
            assertEquals(clamped.directionY, again.directionY, 1e-10)
            assertEquals(clamped.directionZ, again.directionZ, 1e-10)
        }
        val body = basis()
        val behind = FixedWingPilotIntent.normalized(0.0, 0.0, -1.0, 0)!!
        val clamped = FixedWingJoystickBoundary.constrainHeld(behind, body, 1920, 1080)
        assertEquals(-1.0, FixedWingJoystickBoundary.heldAxes(clamped, body, 1920, 1080)!!.vertical.toDouble(), 1e-6)
        assertTrue(clamped.directionZ > 0)
    }

    @Test fun `upward mouse input reaches real elevator throughout banked dives`() {
        val handling = FixedWingHandlingProfile.GAME_JET
        for (pitch in listOf(60.0, 80.0, 89.9, 90.0, 100.0))
        for (roll in listOf(-180.0, -90.0, -45.0, 0.0, 45.0, 90.0, 180.0))
        for (sidePixels in listOf(-2.0, 0.0, 2.0))
        for (firstPerson in listOf(false, true)) {
            val body = basis(0.0, pitch, roll)
            val camera = basis(0.0, pitch, if (firstPerson) roll else 0.0)
            val nose = Vector3d(body.right).cross(body.up).negate()
            val initial = FixedWingPilotIntent.normalized(nose.x, nose.y, nose.z, 0)!!
            val state = FixedWingMouseAimState()
            state.sampleHeld(0, 1_000_000_000L, initial, camera, body, 0.0, 0.0, 1.0, false, false)
            val next = state.sampleHeld(1, 1_050_000_000L, initial, camera, body,
                sidePixels, -40.0, 1.0, false, false) {
                FixedWingJoystickBoundary.constrainHeld(it, body, 1920, 1080)
            }!!
            val axes = FixedWingJoystickBoundary.heldAxes(next, body, 1920, 1080)!!
            val command = next.copy(screenRollInput = axes.horizontal, firstPerson = firstPerson)
            val model = FixedWingFlightModel(handling).also { it.reset(0.0, pitch, roll) }
            val controller = FixedWingMouseAimController(handling)
            val velocity = Vector3d(nose).mul(45.0)
            // Exercise acquisition and steady control after previous heading guidance.
            val previousRay = Vector3d(nose).fma(0.2, body.right).normalize()
            val previous = FixedWingPilotIntent.normalized(previousRay.x, previousRay.y, previousRay.z,
                0, 0.5f, firstPerson)!!
            repeat(12) { controller.update(model, previous, true, false, velocity.x, velocity.y, velocity.z, 1.0) }
            repeat(12) {
                assertTrue(controller.update(model, command, true, false, velocity.x, velocity.y, velocity.z, 1.0))
                assertTrue(controller.elevatorCommand > 0.05,
                    "Lost pull-up pitch=$pitch roll=$roll side=$sidePixels fp=$firstPerson elevator=${controller.elevatorCommand}")
            }
            assertTrue(model.step(0, velocity.x, velocity.y, velocity.z, false, true, surfaces = controller))
            assertTrue(model.elevator > 0.0, "Elevator command did not reach the physical surface")
        }
    }

    @Test fun `upward joystick input remains elevator input through banked dives`() {
        for (pitch in listOf(0.0, 60.0, 80.0, 89.9, 90.0, 100.0))
        for (roll in listOf(-180.0, -90.0, -45.0, 0.0, 45.0, 90.0, 180.0)) {
            val aircraft = basis(0.0, pitch, roll)
            val camera = basis(0.0, pitch, 0.0)
            val nose = Vector3d(aircraft.right).cross(aircraft.up).negate()
            val initial = FixedWingPilotIntent.normalized(nose.x, nose.y, nose.z, 0)!!
            val state = FixedWingMouseAimState()
            state.sampleHeld(0, 1_000_000_000L, initial, camera, aircraft,
                0.0, 0.0, 1.0, false, false)
            val next = state.sampleHeld(1, 1_050_000_000L, initial, camera, aircraft,
                0.0, -40.0, 1.0, false, false)!!
            val ray = Vector3d(next.directionX, next.directionY, next.directionZ)
            assertTrue(ray.dot(aircraft.up) > 0.09,
                "Up input lost pitch authority at pitch=$pitch roll=$roll")
            assertEquals(0.0, ray.dot(aircraft.right), 1e-8,
                "Up input became lateral guidance at pitch=$pitch roll=$roll")
        }
    }

    private fun basis(yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0): FixedWingMouseAimMath.Basis {
        val q = VehicleFlightAttitude.quaternion(yaw.toFloat(), pitch.toFloat(), roll.toFloat())
        return FixedWingMouseAimMath.Basis(q.transform(Vector3d(-1.0, 0.0, 0.0)), q.transform(Vector3d(0.0, 1.0, 0.0)))
    }
    private fun intent() = FixedWingPilotIntent.normalized(0.0, -0.5, kotlin.math.sqrt(0.75), 0)!!
    private fun angle(intent: FixedWingPilotIntent, frame: FixedWingMouseAimMath.Basis): Double {
        val forward = Vector3d(frame.right).cross(frame.up).negate()
        return Math.toDegrees(acos(Vector3d(intent.directionX, intent.directionY, intent.directionZ).dot(forward).coerceIn(-1.0, 1.0)))
    }

    @Test fun `held real steering follows body and loses only half its angle over twenty seconds`() {
        for (fps in listOf(30, 60, 144)) {
            val state = FixedWingMouseAimState()
            var sample = intent()
            for (i in 0..fps * 20) {
                val aircraft = basis(i * 0.03, i * 0.005, i * 0.012)
                val camera = basis(-i * 0.02, 10.0, 0.0)
                sample = state.sampleHeld(i.toLong(), 1_000_000_000L + i * 1_000_000_000L / fps,
                    sample, camera, aircraft, 0.0, 0.0, 1.0, false, false)!!
                assertFalse(state.consumedMouseGesture(i.toLong()), "Body or camera movement must not invent mouse input")
            }
            assertEquals(15.0, angle(sample, basis(fps * 20 * 0.03, fps * 20 * 0.005, fps * 20 * 0.012)), 0.03)
        }
    }

    @Test fun `manual controls and explicit recenter preserve their priority`() {
        val state = FixedWingMouseAimState()
        val body = basis()
        state.sampleHeld(0, 1_000_000_000, intent(), body, body, 0.0, 0.0, 1.0, false, false)
        val manual = state.sampleHeld(1, 1_020_000_000, intent().copy(manualMask = 1), body, body,
            200.0, 200.0, 1.0, false, false)!!
        assertEquals(30.0, angle(manual, body), 0.001)
        assertEquals(1, manual.manualMask)
        val centered = FixedWingPilotIntent.normalized(0.0, 0.0, 1.0, 0)!!
        assertEquals(centered, state.sampleHeld(2, 1_040_000_000, centered, body, body,
            200.0, 200.0, 1.0, false, true))
        state.reset()
        assertEquals(centered, state.sampleHeld(3, 1_060_000_000, centered, body, body,
            200.0, 200.0, 1.0, false, false))
    }
}
