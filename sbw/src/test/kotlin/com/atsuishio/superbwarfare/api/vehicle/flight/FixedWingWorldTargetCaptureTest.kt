package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimMath
import com.atsuishio.superbwarfare.client.input.FixedWingMouseAimState
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingWorldTargetCaptureTest {
    private val h = FixedWingHandlingProfile.GAME_JET
    private fun forward(m: FixedWingFlightModel) = Vector3d(
        2*(m.quaternionX*m.quaternionZ+m.quaternionY*m.quaternionW),
        2*(m.quaternionY*m.quaternionZ-m.quaternionX*m.quaternionW),
        1-2*(m.quaternionX*m.quaternionX+m.quaternionY*m.quaternionY))
    private fun target(yaw: Double, pitch: Double): FixedWingPilotIntent {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return FixedWingPilotIntent.normalized(-sin(y)*cos(p), -sin(p), cos(y)*cos(p), 0)!!
    }
    private fun run(bank: Double, heading: Double, pitch: Double): Triple<Double,Double,Double> {
        val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, bank)
        val c = FixedWingMouseAimController(h)
        val aim = target(heading, pitch)
        var v = Vector3d(0.0, 0.0, 40.0)
        val projection = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), 1280f/720f, 0.1f, 1000f)
        var endError = 0.0; var endRollRate = 0.0
        repeat(400) { tick ->
            val nose = forward(m)
            val camera = FixedWingMouseAimMath.aircraftBasis(m.yawDegrees.toFloat(), m.pitchDegrees.toFloat(), 0f)
            val view = Matrix4f().lookAt(0f, 0f, 0f, nose.x.toFloat(), nose.y.toFloat(), nose.z.toFloat(),
                camera.up.x.toFloat(), camera.up.y.toFloat(), camera.up.z.toFloat())
            val rollInput = FixedWingMouseAimMath.screenRollError(aim, nose.x, nose.y, nose.z, view, projection)!!
            assertTrue(c.update(m, aim.copy(screenRollInput=rollInput), true, false, v.x,v.y,v.z,1.0))
            assertTrue(m.step(tick.toLong(),v.x,v.y,v.z,false,true,
                throttleAxis=if(tick<20) 1.0 else 0.0,engineAvailability=1.0,surfaces=c))
            v.set(m.velocityX,m.velocityY,m.velocityZ)
            if (tick>=360) {
                val error=Math.toDegrees(acos(forward(m).dot(Vector3d(aim.directionX,aim.directionY,aim.directionZ)).coerceIn(-1.0,1.0)))
                endError=max(endError,error); endRollRate=max(endRollRate,abs(m.rollRateDegreesPerSecond))
            }
        }
        return Triple(endError,endRollRate,m.rollDegrees)
    }
    @Test fun realVerticalMouseGestureQualifiesAndCompletesAnInvertedLowerTurn() {
        for (fps in listOf(30, 60, 144)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val controller = FixedWingMouseAimController(h)
            val state = FixedWingMouseAimState()
            val initial = target(0.0, 0.0)
            val basis = FixedWingMouseAimMath.aircraftBasis(0f, 0f, 0f)
            val projection = Matrix4f().perspective(Math.toRadians(70.0).toFloat(), 1280f/720f, 0.1f, 1000f)
            val initialView = Matrix4f().lookAt(0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)
            state.sample(0, 1_000_000_000L, initial, basis, 0.0, 0.0, 1.0, false, false)
            var aim = initial
            var qualified = false
            for (frame in 1..fps) {
                aim = state.sample(frame.toLong(), 1_000_000_000L + frame*1_000_000_000L/fps,
                    aim, basis, 0.0, 400.0*frame/fps, 1.0, false, false)!!
                qualified = state.inversionRequested(initialView, projection, 0.0, 0.0, 1.0)
            }
            assertTrue(qualified, "vertical mouse gesture failed to qualify at $fps FPS")
            var v = Vector3d(0.0, 0.0, 40.0)
            var peakBank = 0.0
            var heldAim: FixedWingPilotIntent? = null
            repeat(400) { tick ->
                val nose = forward(m)
                val camera = FixedWingMouseAimMath.aircraftBasis(m.yawDegrees.toFloat(), m.pitchDegrees.toFloat(), 0f)
                val view = Matrix4f().lookAt(0f, 0f, 0f, nose.x.toFloat(), nose.y.toFloat(), nose.z.toFloat(),
                    camera.up.x.toFloat(), camera.up.y.toFloat(), camera.up.z.toFloat())
                aim = state.sample((fps+tick+1).toLong(), 2_050_000_000L+tick*50_000_000L,
                    aim, camera, 0.0, 400.0, 1.0, false, false)!!
                if (tick == 10) heldAim = aim
                if (tick > 10) {
                    assertEquals(heldAim!!.directionY, aim.directionY, 1e-9)
                    assertEquals(heldAim!!.directionZ, aim.directionZ, 1e-9)
                }
                val roll = FixedWingMouseAimMath.screenRollError(aim, nose.x, nose.y, nose.z, view, projection)!!
                val inverted = state.inversionRequested(view, projection, nose.x, nose.y, nose.z)
                assertTrue(controller.update(m, aim.copy(screenRollInput=roll, inversionRequested=inverted),
                    true, false, v.x, v.y, v.z, 1.0))
                assertTrue(m.step(tick.toLong(), v.x, v.y, v.z, false, true,
                    throttleAxis=if(tick<20) 1.0 else 0.0, engineAvailability=1.0, surfaces=controller))
                v.set(m.velocityX, m.velocityY, m.velocityZ)
                peakBank = max(peakBank, abs(m.rollDegrees))
            }
            val error = Math.toDegrees(acos(forward(m).dot(Vector3d(aim.directionX, aim.directionY, aim.directionZ)).coerceIn(-1.0, 1.0)))
            assertTrue(peakBank > 100.0, "fps=$fps peak=$peakBank")
            assertTrue(error < 3.0, "fps=$fps error=$error")
        }
    }

    @Test fun ordinaryTargetsCaptureWithDecliningScreenTravel() {
        for (heading in listOf(-20.0,-5.0,5.0,20.0)) {
            val result=run(0.0,heading,0.0)
            assertTrue(result.first<2.0,"heading=$heading result=$result")
            assertTrue(result.second<5.0,"heading=$heading result=$result")
        }
    }
    @Test fun screenVerticalAimCanRecoverFromBankedAndInvertedStarts() {
        for (bank in listOf(-180.0,-135.0,-90.0,-45.0,0.0,45.0,90.0,135.0,180.0)) {
            val result=run(bank,0.0,-12.0)
            assertTrue(result.first<3.0,"bank=$bank result=$result")
            assertTrue(result.second<5.0,"bank=$bank result=$result")
        }
    }

    @Test fun largeUnrestrictedTargetsCanBeAcquired() {
        for (heading in listOf(-120.0, -60.0, 60.0, 120.0)) {
            val result = run(0.0, heading, 0.0)
            assertTrue(result.first < 3.0, "heading=$heading result=$result")
            assertTrue(result.second < 5.0, "heading=$heading result=$result")
        }
    }
}
