package com.atsuishio.superbwarfare.api.vehicle.flight

import org.joml.Quaterniond
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class FixedWingManoeuvreSequenceTest {
    private val rad=PI/180.0
    private fun target(yaw:Double,pitch:Double,side:Float=0F,lower:Boolean=false):FixedWingPilotIntent =
        FixedWingPilotIntent(-sin(yaw*rad)*cos(pitch*rad),-sin(pitch*rad),cos(yaw*rad)*cos(pitch*rad),
            0,side,inversionRequested=lower)
    private class Flight {
        val model=FixedWingFlightModel(MiG19FixedWingProfile.HANDLING)
        val controller=FixedWingMouseAimController(MiG19FixedWingProfile.HANDLING)
        var vx=0.0;var vy=0.0;var vz=110.0;var tick=0L
        var invertedPull=0;var peakBank=0.0;var maximumStep=0.0
        init {model.reset(0.0,0.0,0.0)}
        fun step(aim:FixedWingPilotIntent) {
            val q=Quaterniond(model.quaternionX,model.quaternionY,model.quaternionZ,model.quaternionW)
            assertTrue(controller.update(model,aim,true,false,vx,vy,vz,0.25))
            assertTrue(model.step(tick++,vx,vy,vz,false,true,airDensityRatio=0.25,surfaces=controller))
            vx=model.velocityX;vy=model.velocityY;vz=model.velocityZ
            val after=Quaterniond(model.quaternionX,model.quaternionY,model.quaternionZ,model.quaternionW)
            maximumStep=max(maximumStep,Math.toDegrees(2*acos(abs(q.dot(after)).coerceIn(0.0,1.0))))
            peakBank=max(peakBank,abs(model.rollDegrees))
            if (1-2*(model.quaternionX.pow(2)+model.quaternionZ.pow(2)) < -0.1 && model.elevator>0.1) invertedPull++
        }
        fun error(aim:FixedWingPilotIntent):Double {
            val x=model.quaternionX;val y=model.quaternionY;val z=model.quaternionZ;val w=model.quaternionW
            return Math.toDegrees(acos((2*(x*z+y*w)*aim.directionX+2*(y*z-x*w)*aim.directionY+
                (1-2*(x*x+y*y))*aim.directionZ).coerceIn(-1.0,1.0)))
        }
    }

    @Test fun climbThenLowerDiagonalUsesAnInvertedPositivePullAndCaptures() {
        for (side in listOf(-1.0,1.0)) {
            val flight=Flight()
            val climb=target(0.0,-45.0)
            repeat(100){flight.step(climb)}
            assertTrue(flight.model.pitchDegrees < -30.0,"climb setup did not reach actual pitch")
            val lower=target(side*55.0,35.0,(side*0.4).toFloat(),true)
            val initial=flight.error(lower)
            repeat(300){flight.step(lower)}
            assertTrue(flight.invertedPull>0,"climb/lower side=$side bank=${flight.peakBank}")
            assertTrue(flight.error(lower)<3.0,"climb/lower error=${flight.error(lower)} from=$initial")
            assertTrue(flight.maximumStep<8.0)
        }
    }

    @Test fun bankedTurnCanRetargetToGroundBehindWithoutScreenQuadrantPermission() {
        for(side in listOf(-1.0,1.0)) {
            val flight=Flight()
            // Pilot roll keys establish the bank through the real actuator/kernel; no pose override.
            val rollMask=if(side>0) FixedWingPilotIntent.ROLL_RIGHT else FixedWingPilotIntent.ROLL_LEFT
            var steps=0
            while(abs(flight.model.rollDegrees)<85.0 && steps++<80) {
                flight.step(target(0.0,0.0).copy(manualMask=rollMask))
            }
            assertTrue(abs(flight.model.rollDegrees) in 85.0..105.0)
            val behind=target(side*150.0,35.0,(side*0.3).toFloat())
            val initial=flight.error(behind)
            repeat(360){flight.step(behind)}
            assertTrue(flight.invertedPull>0,"behind target side=$side bank=${flight.peakBank}")
            assertTrue(flight.error(behind)<3.0,"behind error=${flight.error(behind)} from=$initial")
            assertTrue(flight.maximumStep<8.0)
        }
    }

    @Test fun movingWorldTargetCanCircleThroughGroundAndReturnToNeutralPitch() {
        for(side in listOf(-1.0,1.0)) {
            val flight=Flight()
            var previousYaw=0.0;var previousPitch=0.0
            val destinations=listOf(55.0 to -30.0,100.0 to 40.0,170.0 to 55.0,
                245.0 to 30.0,310.0 to 5.0,360.0 to 0.0)
            for((yaw,pitch) in destinations) {
                repeat(50){step ->
                    val t=(step+1)/50.0
                    flight.step(target(side*(previousYaw+(yaw-previousYaw)*t),
                        previousPitch+(pitch-previousPitch)*t,(side*0.5).toFloat(),pitch>0.0))
                }
                previousYaw=yaw;previousPitch=pitch
            }
            val final=target(0.0,0.0)
            repeat(300){flight.step(final)}
            assertTrue(flight.invertedPull>0,"swirl did not use inversion side=$side")
            assertTrue(flight.error(final)<3.0,"swirl error=${flight.error(final)} speed=${flight.model.speedMps} stall=${flight.model.stallSeverity} pitch=${flight.model.pitchDegrees} roll=${flight.model.rollDegrees} yaw=${flight.model.yawDegrees} pitchErr=${flight.controller.pitchErrorDegrees} yawErr=${flight.controller.yawErrorDegrees} commands=${flight.controller.elevatorCommand},${flight.controller.aileronCommand},${flight.controller.rudderCommand} rates=${flight.model.pitchRateDegreesPerSecond},${flight.model.yawRateDegreesPerSecond}")
            // Nose capture precedes the deliberately gentle wings-level rollout.
            repeat(100){flight.step(final)}
            assertTrue(flight.error(final)<3.0)
            assertTrue(abs(flight.model.rollDegrees)<3.0,"swirl settled bank=${flight.model.rollDegrees} error=${flight.error(final)} rollRate=${flight.model.rollRateDegreesPerSecond}")
            assertTrue(flight.maximumStep<8.0)
        }
    }
}
