package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingWorldAimCameraTest {
    @Test fun existingFreelookAndRenderCadenceContractsRemainValid() {
        FixedWingFreelookReturnTest.main(emptyArray())
        MovingFreelookReleaseTest.main(emptyArray())
    }
    private fun ray(yaw: Double, pitch: Double): FixedWingPilotIntent {
        val y = Math.toRadians(yaw); val p = Math.toRadians(pitch)
        return FixedWingPilotIntent.normalized(-sin(y)*cos(p), -sin(p), cos(y)*cos(p), 0)!!
    }

    @Test fun followHistorySurvivesOrdinaryTicksAndStrengthDoesNotLimitLookAngle() {
        for (fps in listOf(30, 60, 144)) for (strength in listOf(0.0, 0.5, 1.0)) {
            val camera = FixedWingCameraMotion()
            for (frame in 0..fps*2) camera.advance(frame.toLong(), frame*1_000_000_000L/fps,
                ray(20.0, -10.0), 0.0, 0.0, strength, false)
            val offset = camera.sample()
            assertEquals(if (strength == 0.0) 0.0 else 20.0, offset.first, 0.01)
            assertEquals(if (strength == 0.0) 0.0 else -10.0, offset.second, 0.01)
        }
    }

    @Test fun fullCircleAndVerticalLoopStayContinuousAndResetToForward() {
        for (vertical in listOf(false, true)) {
            val camera = FixedWingCameraMotion()
            var previous = 0.0
            for (frame in 0..840) {
                val angle = minOf(frame * 0.5, 360.0)
                camera.advance(frame.toLong(), frame * 16_666_667L,
                    if (vertical) ray(0.0, angle) else ray(angle, 0.0), 0.0, 0.0, 0.5, false)
                val current = if (vertical) camera.sample().second else camera.sample().first
                assertTrue(abs(current - previous) < 2.0, "unwanted branch jump at $frame")
                previous = current
            }
            assertEquals(360.0, previous, 0.02)
            camera.reset()
            assertEquals(0.0 to 0.0, camera.sample())
        }
    }

    @Test fun keyboardOverridesDoNotFreezeCameraChaseOrFreshMouseTargets() {
        for (fps in listOf(30,60,144)) {
            val manualCamera=FixedWingCameraMotion()
            val reference=FixedWingCameraMotion()
            for(frame in 0..fps*4) {
                val yaw=10.0*sin(frame.toDouble()/fps)
                val pitch=frame.toDouble()/fps*8.0
                val target=ray(yaw+20.0,pitch-12.0)
                val time=frame*1_000_000_000L/fps
                manualCamera.advance(frame.toLong(),time,target,yaw,pitch,0.5,frame in fps..fps*3)
                reference.advance(frame.toLong(),time,target,yaw,pitch,0.5,false)
                assertEquals(reference.sample().first,manualCamera.sample().first,1e-9)
                assertEquals(reference.sample().second,manualCamera.sample().second,1e-9)
            }
            assertTrue(abs(manualCamera.sample().first)>5.0)
        }
    }

    @Test fun verticalCrossingDoesNotInventSidewaysCameraTurn() {
        for (basePitch in listOf(-100.0, -90.0, -80.0, 80.0, 90.0, 100.0)) {
            val camera = FixedWingCameraMotion()
            for (frame in 0..120) camera.advance(frame.toLong(), frame*16_666_667L,
                ray(30.0, basePitch-12.0), 30.0, basePitch, 1.0, false)
            val offset = camera.sample()
            assertEquals(0.0, offset.first, 0.01, "base pitch=$basePitch")
            assertEquals(-12.0, offset.second, 0.01, "base pitch=$basePitch")
        }
    }
}
