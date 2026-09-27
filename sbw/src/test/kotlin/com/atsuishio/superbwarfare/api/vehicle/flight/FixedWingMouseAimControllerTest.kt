package com.atsuishio.superbwarfare.api.vehicle.flight

import java.util.UUID
import kotlin.math.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixedWingMouseAimControllerTest {
    @Test fun deliberateSideTravelHasAuthorityWithoutTurningPitchCorrectionsIntoRolls() {
        for (side in listOf(-1.0, 1.0)) for (pitch in listOf(-12.0, 12.0)) {
            fun command(travel: Double): Pair<Double, Double> {
                val model = FixedWingFlightModel(h); model.reset(0.0, 0.0, 0.0)
                val controller = FixedWingMouseAimController(h)
                val x = -side * sin(25.0 * rad)
                val y = sin(pitch * rad)
                val length = sqrt(x * x + y * y + 1.0)
                val target = FixedWingPilotIntent.normalized(
                    x / length, y / length, 1.0 / length, 0, (side * travel).toFloat())!!
                assertTrue(controller.update(model, target, true, false, 0.0, 0.0, 40.0, 1.0))
                return abs(controller.aileronCommand) to controller.elevatorCommand
            }
            val fine = command(0.05)
            val middle = command(0.50)
            val deliberate = command(0.75)
            assertTrue(fine.first < 0.05, "near-center pitch correction rolls too strongly: $fine")
            // Ailerons are 15% less sensitive, and less again with the indicator below the nose.
            val sensitivity = if (pitch < 0.0) 0.6 else 0.85
            assertTrue(middle.first > 0.60 * sensitivity, "half-radius intentional roll remains too weak: $middle")
            assertTrue(deliberate.first > 0.90 * sensitivity, "outer intentional roll lacks full authority: $deliberate")
            assertTrue(deliberate.first > fine.first * 4.0,
                "deliberate lateral travel lacks roll authority: fine=$fine deliberate=$deliberate")
            assertTrue(fine.second * pitch > 0.1, "fine pitch authority was lost: $fine")
            assertTrue(deliberate.second * pitch > 0.1, "pitch authority was lost while rolling")
        }
    }

    @Test fun mouseRollResponseIsSmoothMonotonicAndKeepsFullAuthority() {
        val response = FixedWingMouseAimController::mouseRollResponse
        assertEquals(0.0, response(0.0), 0.0)
        assertEquals(1.0, response(1.0), 0.0)
        assertEquals(-1.0, response(-1.0), 0.0)
        assertEquals(0.3125, response(0.5), 1e-12)
        var previous = response(-1.0)
        for (index in -999..1000) {
            val input = index / 1000.0
            val value = response(input)
            assertTrue(value > previous)
            assertTrue(abs(value) <= abs(input) + 1e-12)
            assertEquals(-value, response(-input), 1e-12)
            assertTrue(value - previous <= 0.002 + 1e-12)
            previous = value
        }
    }

    @Test fun limitedNegativeLoadDoesNotDisableMouseElevatorInBankedAndInvertedFlight() {
        val handling = h.copy(maximumNegativeLoadFactor = 0.5)
        for (bank in listOf(-180.0, -90.0, -45.0, 0.0, 45.0, 90.0, 180.0))
        for (pitch in listOf(-12.0, 12.0)) for (fp in listOf(false, true)) {
            val model = FixedWingFlightModel(handling); model.reset(0.0, 0.0, bank)
            val controller = FixedWingMouseAimController(handling)
            val target = toward(model, pitch).copy(screenRollInput = 0F, firstPerson = fp)
            val before = dot(forward(model), direction(target))
            assertTrue(controller.update(model, target, true, false, 0.0, 0.0, 40.0, 1.0))
            assertTrue(controller.elevatorCommand * pitch > 0.1,
                "mouse elevator suppressed bank=$bank pitch=$pitch fp=$fp command=${controller.elevatorCommand}")
            assertTrue(model.step(0, 0.0, 0.0, 40.0, false, true, surfaces = controller))
            assertTrue(model.elevator * pitch > 0.0)
            if (abs(bank) <= 90.0 || pitch > 0.0) {
                assertTrue(dot(forward(model), direction(target)) > before,
                    "mouse input failed to turn nose toward target bank=$bank pitch=$pitch")
            } else {
                // A half-G negative-load airframe cannot pitch upward against gravity
                // while inverted, even though its elevator must still accept the command.
                assertEquals(0.0, model.pitchRateDegreesPerSecond, 1e-10)
            }
            assertTrue(model.liftAccelerationMps2 >=
                -handling.maximumNegativeLoadFactor * handling.gravityMps2 - 1e-6)
        }
    }

    @Test fun deliberateLowerMouseTargetKeepsElevatorAuthorityWhileRolling() {
        for (side in listOf(-1.0, 1.0)) for (fp in listOf(false, true)) {
            val model = FixedWingFlightModel(h); model.reset(0.0, 0.0, 0.0)
            val controller = FixedWingMouseAimController(h)
            val length = sqrt(1.0 + 0.18 * 0.18 * 2.0)
            val target = FixedWingPilotIntent(-side * 0.18 / length, -0.18 / length, 1.0 / length,
                0, (side * 0.3).toFloat(), fp, inversionRequested = true)
            assertTrue(controller.update(model, target, true, false, 0.0, 0.0, 40.0, 1.0))
            assertTrue(controller.elevatorCommand < -0.1, "downward mouse demand was suppressed")
            assertTrue(abs(controller.aileronCommand) > 0.1)
        }
    }

    @Test fun movingNearCenterTargetDoesNotAcquireLevelCapture() {
        val model = FixedWingFlightModel(h); model.reset(0.0, 0.0, 25.0)
        val controller = FixedWingMouseAimController(h)
        val captured = controller.javaClass.getDeclaredField("captured").also { it.isAccessible = true }
        repeat(200) { tick ->
            val target = toward(model, if (tick % 2 == 0) 0.4 else -0.4).copy(screenRollInput = 0.05F)
            assertTrue(controller.update(model, target, true, false, 0.0, 0.0, 40.0, 1.0))
            assertFalse(captured.getBoolean(controller), "human correction acquired center capture")
            assertTrue(controller.elevatorCommand * (if (tick % 2 == 0) 1.0 else -1.0) > 0.0)
        }
        val held = toward(model, 0.0).copy(screenRollInput = 0F)
        repeat(40) { assertTrue(controller.update(model, held, true, false, 0.0, 0.0, 40.0, 1.0)) }
        assertTrue(captured.getBoolean(controller))
    }

    @Test fun bankedHeadingRespectsJointElevatorRudderFeasibility() {
        for (speed in listOf(24.0,45.0)) for (side in listOf(-1.0,1.0))
        for (heading in listOf(8.0,30.0)) for (fp in listOf(false,true)) {
            val m=FixedWingFlightModel(h);m.reset(0.0,0.0,45.0*side)
            val c=FixedWingMouseAimController(h)
            val target=FixedWingPilotIntent(-sin(heading*side*rad),0.0,cos(heading*rad),
                0,(side*.5).toFloat(),fp)
            repeat(12){assertTrue(c.update(m,target,true,false,0.0,0.0,speed,1.0))}
            val qx=m.quaternionX;val qy=m.quaternionY;val qz=m.quaternionZ;val qw=m.quaternionW
            val upY=1.0-2.0*(qx*qx+qz*qz)
            val rightY=2.0*(qx*qy+qz*qw)
            val p=c.elevatorCommand*(h.pitchResponseLinearFraction+
                (1.0-h.pitchResponseLinearFraction)*abs(c.elevatorCommand))*
                h.gamePitchRateDegreesPerSecond*h.pitchAirflowAuthority(speed*speed,1.0)
            val yaw=c.rudderCommand*h.rudderRateDegreesPerSecond*FixedWingFlightModel.YAW_AUTHORITY_SCALE
            assertTrue(p>0.0)
            assertTrue(abs(yaw)<=h.rudderRateDegreesPerSecond*FixedWingFlightModel.YAW_AUTHORITY_SCALE+1e-9)
            assertEquals(0.0,p*upY+yaw*rightY,1e-8,
                "A level world target must not gain elevator-only climb")
            assertTrue(c.elevatorCommand in -1.0..1.0 && c.aileronCommand in -1.0..1.0)
        }
    }

    @Test fun alignedCockpitPredictionIsStrongerUntilPhysicalSaturation() {
        fun command(fp:Boolean,manual:Int=0):Double {
            val m=FixedWingFlightModel(h);m.reset(0.0,0.0,0.0)
            val c=FixedWingMouseAimController(h)
            val target=FixedWingPilotIntent(0.0,sin(4.0*rad),cos(4.0*rad),manual,0f,fp)
            repeat(12){assertTrue(c.update(m,target,true,false,0.0,0.0,45.0,1.0))}
            return c.elevatorCommand
        }
        assertTrue(command(true)>command(false))
        assertTrue(command(true) in 0.0..1.0)
        assertEquals(command(true,FixedWingPilotIntent.PITCH_UP),
            command(false,FixedWingPilotIntent.PITCH_UP),0.0)
    }

    private val h = FixedWingHandlingProfile.GAME_JET
    private val rad = PI / 180.0
    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
    private fun forward(m: FixedWingFlightModel) = doubleArrayOf(
        2.0 * (m.quaternionX * m.quaternionZ + m.quaternionY * m.quaternionW),
        2.0 * (m.quaternionY * m.quaternionZ - m.quaternionX * m.quaternionW),
        1.0 - 2.0 * (m.quaternionX * m.quaternionX + m.quaternionY * m.quaternionY))
    private fun up(m: FixedWingFlightModel) = doubleArrayOf(
        2.0 * (m.quaternionX * m.quaternionY - m.quaternionZ * m.quaternionW),
        1.0 - 2.0 * (m.quaternionX * m.quaternionX + m.quaternionZ * m.quaternionZ),
        2.0 * (m.quaternionY * m.quaternionZ + m.quaternionX * m.quaternionW))
    private fun toward(m: FixedWingFlightModel, degrees: Double, mask: Int = 0): FixedWingPilotIntent {
        val f = forward(m); val u = up(m)
        val d = DoubleArray(3) { f[it] * cos(degrees * rad) + u[it] * sin(degrees * rad) }
        return requireNotNull(FixedWingPilotIntent.normalized(d[0], d[1], d[2], mask))
    }
    private fun direction(i: FixedWingPilotIntent) = doubleArrayOf(i.directionX, i.directionY, i.directionZ)
    private fun step(
        m: FixedWingFlightModel, c: FixedWingMouseAimController, i: FixedWingPilotIntent?,
        tick: Long, vx: Double, vy: Double, vz: Double, ground: Boolean = false,
        enabled: Boolean = true, throttle: Double = 0.0, engine: Double = 0.0,
        density: Double = 1.0,
    ) {
        assertTrue(c.update(m, i, enabled, ground, vx, vy, vz, density))
        val previous = listOf(m.elevator, m.aileron, m.rudder)
        assertTrue(m.step(tick, vx, vy, vz, ground, enabled, throttleAxis = throttle,
            engineAvailability = engine, airDensityRatio = density, surfaces = c))
        val requests = listOf(c.elevatorCommand, c.aileronCommand, c.rudderCommand)
        val actual = listOf(m.elevator, m.aileron, m.rudder)
        val rates = listOf(h.elevatorTravelPerSecond, h.aileronTravelPerSecond, h.rudderTravelPerSecond)
        for (axis in actual.indices) {
            val target = if (enabled) requests[axis] else 0.0
            assertTrue(abs(actual[axis] - previous[axis]) <= rates[axis] * 0.05 + 1e-12)
            assertTrue(abs(target - actual[axis]) <= abs(target - previous[axis]) + 1e-12)
            if (abs(target - previous[axis]) <= rates[axis] * 0.05) {
                assertEquals(target, actual[axis], 1e-12)
            }
        }
        val work = m.stepThrustWorkPerKg + m.stepGravityWorkPerKg + m.stepDragWorkPerKg +
            m.stepSideWorkPerKg + m.stepLiftWorkPerKg + m.stepGroundResistanceWorkPerKg
        assertEquals(m.postStepKineticEnergyPerKg - m.preStepKineticEnergyPerKg, work, 1.0e-8)
        assertTrue(abs(m.stepLiftWorkPerKg) < 1.0e-8)
        assertTrue(m.stepDragWorkPerKg <= 1.0e-10 && m.stepSideWorkPerKg <= 1.0e-10)
        assertEquals(0.0, m.virtualPitchTarget, 0.0)
        assertEquals(0.0, m.virtualRollTarget, 0.0)
    }

    @Test fun bankedAndInvertedPitchActsThroughTheRealKernel() {
        for ((bank, expected) in listOf(90.0 to 1.0, -90.0 to -1.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, bank)
            val c = FixedWingMouseAimController(h)
            val i = FixedWingPilotIntent(-sin(12.0 * rad), 0.0, cos(12.0 * rad))
            val before = dot(forward(m), direction(i))
            step(m, c, i, 0L, 0.0, 0.0, 24.0)
            assertTrue(m.elevator * expected > 0.0)
            assertEquals(0.0, m.aileron, 1.0e-9)
            assertEquals(0.0, m.rudder, 1.0e-9)
            assertTrue(dot(forward(m), direction(i)) > before)
        }
        val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 180.0)
        val c = FixedWingMouseAimController(h)
        step(m, c, FixedWingPilotIntent(0.0, sin(9.0 * rad), cos(9.0 * rad)), 0L, 0.0, 0.0, 24.0)
        assertTrue(m.elevator < 0.0)
        assertEquals(0.0, m.aileron, 1.0e-9)
        assertEquals(0.0, m.rudder, 1.0e-9)
    }

    @Test fun allAttitudePitchPlaneSignsAndBoundedPhysicalResponse() {
        var count = 0
        for (yaw in listOf(-180.0,-179.9,-90.0,0.0,90.0,179.9,180.0))
            for (pitch in listOf(-91.0,-90.0,-89.9,-45.0,0.0,45.0,89.9,90.0,91.0))
                for (roll in listOf(-180.0,-90.0,-45.0,0.0,45.0,90.0,180.0))
                    for (error in listOf(-25.0,-5.0,5.0,25.0)) {
                        val m = FixedWingFlightModel(h); m.reset(yaw,pitch,roll)
                        val c = FixedWingMouseAimController(h)
                        val i = toward(m,error); val f = forward(m)
                        val before = dot(f,direction(i))
                        step(m,c,i,0L,f[0]*24.0,f[1]*24.0,f[2]*24.0)
                        assertTrue(m.elevator * error > 0.0)
                        assertEquals(0.0,m.aileron,1.0e-8)
                        assertEquals(0.0,m.rudder,1.0e-8)
                        assertTrue(dot(forward(m),direction(i)) > before)
                        assertTrue(abs(m.pitchRateDegreesPerSecond) <= h.gamePitchRateDegreesPerSecond)
                        count++
                    }
        assertEquals(1764,count)
    }

    @Test fun capturePlaneDoesNotFlipAcrossTheVerticalPole() {
        val m=FixedWingFlightModel(h)
        val c=FixedWingMouseAimController(h)
        for (pitch in listOf(-89.8,-89.9,-90.0,-90.1,-90.2)) {
            m.reset(0.0,pitch,45.0)
            val f=forward(m)
            val i=requireNotNull(FixedWingPilotIntent.normalized(f[0],f[1],f[2],0))
            assertTrue(c.update(m,i,true,false,f[0]*24.0,f[1]*24.0,f[2]*24.0,1.0))
            assertTrue(abs(c.aileronCommand)<1.0e-4)
        }
    }

    @Test fun manualEndpointsAndOpposingKeysOverrideTheRealKernel() {
        val m=FixedWingFlightModel(h); m.reset(0.0,0.0,0.0)
        val c=FixedWingMouseAimController(h); val up=toward(m,25.0)
        for ((mask,pitch,roll) in listOf(Triple(1,-1.0,0.0),Triple(2,1.0,0.0),
            Triple(3,0.0,0.0),Triple(4,0.0,-1.0),Triple(8,0.0,1.0),
            Triple(12,0.0,0.0),Triple(15,0.0,0.0))) {
            c.reset(); m.reset(0.0,0.0,0.0)
            val i=up.copy(manualMask=mask)
            repeat(20) { n ->
                step(m,c,i,n.toLong(),0.0,0.0,24.0)
                if ((mask and 3)!=0) {
                    assertEquals(pitch,c.elevatorCommand,0.0)
                    if (n >= 10) assertEquals(pitch,m.elevator,1e-12)
                }
                if ((mask and 12)!=0) {
                    assertEquals(roll,c.aileronCommand,0.0)
                    if (n >= 10) assertEquals(roll,m.aileron,1e-12)
                }
            }
        }
    }

    @Test fun heldKeysDoNotSynthesizeCommandsOnOtherAxes() {
        val m=FixedWingFlightModel(h); m.reset(0.0,0.0,0.0)
        val i=FixedWingPilotIntent(0.4,0.3,sqrt(0.75))
        val baseline=FixedWingMouseAimController(h)
        assertTrue(baseline.update(m,i,true,false,0.0,0.0,24.0,1.0))
        for(mask in listOf(1,2,3,4,8,12)) {
            val c=FixedWingMouseAimController(h)
            assertTrue(c.update(m,i.copy(manualMask=mask),true,false,0.0,0.0,24.0,1.0))
            assertEquals(baseline.rudderCommand,c.rudderCommand,0.0)
            if((mask and 3)!=0) assertEquals(0.0,c.aileronCommand,0.0)
            else assertEquals(baseline.elevatorCommand,c.elevatorCommand,0.0)
        }
    }

    @Test fun manualReleaseReturnsToUnchangedAimInThreeUpdates() {
        val m=FixedWingFlightModel(h); m.reset(0.0,0.0,0.0)
        val c=FixedWingMouseAimController(h); val i=toward(m,25.0)
        assertTrue(c.update(m,i.copy(manualMask=1),true,false,0.0,0.0,24.0,1.0))
        assertEquals(-1.0,c.elevatorCommand,0.0)
        val positions=mutableListOf<Double>()
        repeat(3) {
            assertTrue(c.update(m,i,true,false,0.0,0.0,24.0,1.0))
            positions+=c.elevatorCommand
        }
        assertTrue(positions[0] > -1.0 && positions[0] < 1.0)
        assertTrue(positions.zipWithNext().all { (a,b) -> b>=a })
        assertEquals(1.0,positions.last(),1.0e-12)
    }

    @Test fun taxiYawIsWorldUpAndAileronsHaveNoLowSpeedAuthority() {
        val m=FixedWingFlightModel(h); val c=FixedWingMouseAimController(h)
        val target=FixedWingPilotIntent(-sin(30.0*rad),0.0,cos(30.0*rad),15)
        m.reset(0.0,10.0,0.0)
        step(m,c,target,0L,0.0,0.0,0.0,ground=true)
        assertEquals(0.0,m.yawRateDegreesPerSecond,0.0)
        c.reset(); m.reset(0.0,10.0,0.0)
        step(m,c,target,1L,0.0,0.0,2.0,ground=true)
        assertTrue(m.yawDegrees > 0.0)
        assertEquals(10.0,m.pitchDegrees,1.0e-8)
        assertEquals(0.0,m.rollDegrees,1.0e-8)
        c.reset(); m.reset(0.0,0.0,0.0)
        val roll=FixedWingPilotIntent(0.0,0.0,1.0,8)
        repeat(30) { step(m,c,roll,it.toLong(),0.0,0.0,8.0,ground=true) }
        assertEquals(1.0,m.aileron,0.0)
        assertEquals(0.0,c.groundRollAuthority,0.0)
        assertEquals(0.0,m.rollRateDegreesPerSecond,1.0e-10)
    }

    @Test fun rotationGateHasDensityScalingAndGroundContactHysteresis() {
        for (h in listOf(FixedWingHandlingProfile.GAME_JET,MiG19FixedWingProfile.HANDLING,
            FixedWingReferenceHandling.scale(FixedWingHandlingProfile.GAME_JET,0.5))) {
            for (density in listOf(0.2,0.5,1.0,1.2)) {
                val g=FixedWingGroundControlGate(h); val r=g.rotationReferenceSpeedMps/sqrt(density)
                repeat(20) { g.update(true,r*0.89,density) }
                assertEquals(0.0,g.rollAuthority,0.0)
                g.update(true,r*0.91,density); assertTrue(g.rollAuthority>0.0)
                g.update(true,r*0.88,density); assertTrue(g.rollAuthority>0.0)
                repeat(5) { g.update(true,r*0.84,density) }
                assertEquals(0.0,g.rollAuthority,1.0e-12)
                g.update(false,r*0.3,density); assertEquals(0.0,g.rollAuthority,0.0)
                g.update(true,r*0.3,density); assertEquals(0.0,g.rollAuthority,0.0)
                repeat(2) { g.update(false,r*0.3,density); assertEquals(0.0,g.rollAuthority,0.0) }
                g.update(false,r*0.3,density); assertEquals(0.2,g.rollAuthority,1.0e-12)
                repeat(8) { g.update(false,r*0.3,density) }
                assertEquals(1.0,g.rollAuthority,1.0e-12)
                g.update(true,r*1.1,density); assertEquals(1.0,g.rollAuthority,1.0e-12)
                repeat(8) { g.update(true,r*0.3,density) }
                assertEquals(0.0,g.rollAuthority,1.0e-12)
            }
        }
    }

    @Test fun centeringDoesNotResetGroundRollAuthority() {
        val m=FixedWingFlightModel(h); m.reset(0.0,0.0,0.0)
        val c=FixedWingMouseAimController(h)
        val i=FixedWingPilotIntent(0.0,0.0,1.0,8)
        repeat(10) { assertTrue(c.update(m,i,true,true,0.0,0.0,24.0,1.0)) }
        assertEquals(1.0,c.groundRollAuthority,0.0)
        c.resetGuidance()
        assertEquals(1.0,c.groundRollAuthority,0.0)
        assertTrue(c.update(m,i,true,true,0.0,0.0,24.0,1.0))
        assertEquals(1.0,c.groundRollAuthority,0.0)
        assertEquals(1.0,c.aileronCommand,0.0)
    }

    @Test fun targetPersistsButManualStateExpiresAndOldEpochNeverRevives() {
        val owner=UUID(1L,2L); val state=FixedWingPilotIntentState()
        state.bind(owner,0.0,0.0,1.0); val first=state.controlEpoch
        assertTrue(state.offer(owner,first,1L,10L,1.0,0.0,0.0,3))
        assertEquals(3,state.sample(20L)!!.manualMask)
        assertEquals(0,state.sample(21L)!!.manualMask)
        assertEquals(1.0,state.sample(10_000L)!!.directionX,0.0)
        assertFalse(state.offer(owner,first,1L,30L,0.0,0.0,1.0,1))
        assertFalse(state.offer(owner,first,2L,30L,Double.NaN,0.0,1.0,0))
        state.clear(); state.bind(owner,0.0,0.0,1.0)
        assertNotEquals(first,state.controlEpoch)
        assertFalse(state.offer(owner,first,99L,31L,1.0,0.0,0.0,1))
        assertTrue(state.offer(owner,state.controlEpoch,2L,31L,0.0,0.0,1.0,0))
        assertFalse(state.offer(UUID(2L,3L),state.controlEpoch,3L,32L,0.0,0.0,1.0,0))
        val fresh=FixedWingPilotIntentState(); fresh.bind(owner,0.0,0.0,1.0)
        assertNotEquals(state.controlEpoch,fresh.controlEpoch)
    }

    @Test fun ordinaryBankedTurnCapturesWhilePreservingKernelWorkAccounting() {
        val m=FixedWingFlightModel(h); m.reset(0.0,-h.trimAngleDegrees,0.0)
        val c=FixedWingMouseAimController(h); val pitch=h.trimAngleDegrees*rad
        val intent=FixedWingPilotIntent(-sin(30.0*rad)*cos(pitch),sin(pitch),cos(30.0*rad)*cos(pitch))
        var vx=0.0; var vy=0.0; var vz=24.0; var firstCapture=-1
        for (tick in 0..399) {
            step(m,c,intent,tick.toLong(),vx,vy,vz,throttle=if(tick<14)1.0 else 0.0,engine=1.0)
            vx=m.velocityX; vy=m.velocityY; vz=m.velocityZ
            val error=acos(dot(forward(m),direction(intent)).coerceIn(-1.0,1.0))/rad
            if(error<2.0 && firstCapture<0) firstCapture=tick
            assertTrue(error.isFinite())
            assertTrue(abs(m.rollDegrees) <= 85.0, "transient bank=${m.rollDegrees} tick=$tick")
        }
        assertTrue(firstCapture in 1..400,"firstCapture="+firstCapture)
        val finalError=acos(dot(forward(m),direction(intent)).coerceIn(-1.0,1.0))/rad
        assertTrue(finalError<2.0,"error="+finalError)
        assertTrue(abs(m.rollDegrees)<12.0,"bank="+m.rollDegrees)
    }


    @Test fun passiveWeathercockYawIsCounteredThroughTheReportedRudder() {
        for (lateral in listOf(-8.0, -5.0, -2.0, 2.0, 5.0, 8.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(h)
            val passive = FixedWingFlightModel(h); passive.reset(0.0, 0.0, 0.0)
            assertTrue(passive.step(0L, lateral, 0.0, 24.0, false, true, engineAvailability = 0.0))
            step(m, c, FixedWingPilotIntent(0.0, 0.0, 1.0), 0L, lateral, 0.0, 24.0)
            assertTrue(m.rudder * lateral < 0.0)
            assertTrue(abs(m.rudder) <= 1.0)
            assertTrue(abs(m.yawRateDegreesPerSecond) < abs(passive.yawRateDegreesPerSecond))
            assertEquals(0.0, m.elevator, 1.0e-12)
            assertEquals(0.0, m.aileron, 1.0e-12)
            repeat(60) { tick ->
                step(m, c, FixedWingPilotIntent(0.0, 0.0, 1.0), (tick + 1).toLong(), lateral, 0.0, 24.0)
            }
            assertTrue(abs(m.yawRateDegreesPerSecond) < 0.2,
                "rudder must settle after finite travel: ${m.yawRateDegreesPerSecond}")
        }
    }

    @Test fun sustainedCaptureAcrossInitialAirspeedAndBank() {
        for (speed in listOf(16.0, 20.0, 24.0, 30.0, 40.0)) {
            for (bank in listOf(-90.0, -45.0, 0.0, 45.0, 90.0, 180.0)) {
                val m = FixedWingFlightModel(h); m.reset(0.0, -h.trimAngleDegrees, bank)
                val c = FixedWingMouseAimController(h)
                val pitch = h.trimAngleDegrees * rad
                val intent = FixedWingPilotIntent(
                    -sin(30.0 * rad) * cos(pitch), sin(pitch), cos(30.0 * rad) * cos(pitch))
                var vx = 0.0; var vy = 0.0; var vz = speed; var firstCapture = -1
                for (tick in 0 until 1200) {
                    step(m, c, intent, tick.toLong(), vx, vy, vz,
                        throttle = if (tick < 14) 1.0 else 0.0, engine = 1.0)
                    vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                    val error = acos(dot(forward(m), direction(intent)).coerceIn(-1.0, 1.0)) / rad
                    if (error <= 1.0 && firstCapture < 0) firstCapture = tick
                    if (tick >= 800) {
                        val context = "speed=$speed bank=$bank tick=$tick"
                        assertTrue(error < 2.0, "$context error=$error")
                        assertTrue(abs(m.rollDegrees) < 12.0, "$context roll=${m.rollDegrees}")
                    }
                }
                assertTrue(firstCapture in 1..400,
                    "speed=$speed bank=$bank firstCapture=$firstCapture")
            }
        }
    }

    @Test fun headingChangesStayCapturedAcrossDensityAndInitialBank() {
        for (density in listOf(1.0, 0.65)) {
            val speeds = if (density == 1.0) listOf(16.0, 24.0, 40.0) else listOf(24.0, 32.0, 44.0)
            val banks = if (density == 1.0) listOf(-90.0, 0.0, 90.0) else listOf(-60.0, 0.0, 60.0)
            for (speed in speeds) for (bank in banks) {
                val m = FixedWingFlightModel(h); m.reset(0.0, -h.trimAngleDegrees, bank)
                val c = FixedWingMouseAimController(h)
                val pitch = h.trimAngleDegrees * rad
                var vx = 0.0; var vy = 0.0; var vz = speed; var tick = 0L
                for (heading in listOf(30.0, -30.0, 60.0, -60.0, 0.0)) {
                    val intent = FixedWingPilotIntent(
                        -sin(heading * rad) * cos(pitch), sin(pitch), cos(heading * rad) * cos(pitch))
                    repeat(400) { phaseTick ->
                        step(m, c, intent, tick, vx, vy, vz,
                            throttle = if (tick < 14L) 1.0 else 0.0, engine = 1.0,
                            density = density)
                        vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                        if (bank == 0.0) {
                            assertTrue(abs(m.rollDegrees) <= 85.0,
                                "transient density=$density speed=$speed heading=$heading tick=$phaseTick bank=${m.rollDegrees}")
                        }
                        if (phaseTick >= 240) {
                            val error = acos(dot(forward(m), direction(intent)).coerceIn(-1.0, 1.0)) / rad
                            val context = "density=$density speed=$speed bank=$bank heading=$heading tick=$phaseTick"
                            assertTrue(error < 2.0, "$context error=$error")
                            assertTrue(abs(m.rollDegrees) < 12.0, "$context roll=${m.rollDegrees}")
                        }
                        tick++
                    }
                }
            }
        }
    }

    @Test fun levelFlightHeadingReversalsRespectTransientBankAndEnergy() {
        for (density in listOf(1.0, 0.65)) for (speed in listOf(24.0, 40.0)) {
            val m = FixedWingFlightModel(h)
            m.reset(0.0, -h.trimAngleDegrees, 0.0)
            val c = FixedWingMouseAimController(h)
            var vx = 0.0; var vy = 0.0; var vz = speed
            var altitude = 300.0; var tick = 0L
            for (heading in listOf(0.0, -30.0, 30.0, 0.0)) {
                repeat(400) { phaseTick ->
                    val actualSpeed = max(sqrt(vx * vx + vy * vy + vz * vz), h.minimumControlSpeedMps)
                    val ratio = h.liftReferenceSpeedMps / actualSpeed
                    val bankCosine = max(0.3, cos(m.rollDegrees * rad))
                    val trimAlpha = (ratio * ratio /
                        (density * h.normalizedLiftSlopePerDegree * bankCosine))
                        .coerceIn(0.0, h.stallAngleDegrees * 0.8)
                    val climb = ((300.0 - altitude) * 0.35).coerceIn(-5.0, 5.0)
                    val aimPitch = atan(bankCosine * tan(trimAlpha * rad)) +
                        asin((climb / actualSpeed).coerceIn(-0.5, 0.5))
                    val intent = FixedWingPilotIntent(-sin(heading * rad) * cos(aimPitch),
                        sin(aimPitch), cos(heading * rad) * cos(aimPitch))
                    step(m, c, intent, tick, vx, vy, vz,
                        throttle = if (tick < 14) 1.0 else 0.0, engine = 1.0, density = density)
                    vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                    altitude += vy / 20.0
                    assertTrue(abs(m.rollDegrees) <= 85.0,
                        "density=$density speed=$speed heading=$heading tick=$phaseTick bank=${m.rollDegrees}")
                    if (phaseTick >= 300) {
                        val error = acos(dot(forward(m), direction(intent)).coerceIn(-1.0, 1.0)) / rad
                        assertTrue(error < 2.0, "level-flight heading capture error=$error")
                    }
                    tick++
                }
            }
        }
    }

    @Test fun geometricSideTargetStartsRollAtEveryAttitude() {
        for (yaw in listOf(-170.0, 0.0, 170.0))
            for (pitch in listOf(-90.0, -45.0, 0.0, 45.0, 90.0))
                for (bank in listOf(-180.0, -100.0, -45.0, 0.0, 45.0, 100.0, 180.0))
                    for (input in listOf(-1f, -0.4f, 0.4f, 1f)) {
                        val m = FixedWingFlightModel(h); m.reset(yaw, pitch, bank)
                        val c = FixedWingMouseAimController(h)
                        val f = forward(m)
                        val u = up(m)
                        val bodyRight = doubleArrayOf(u[1] * f[2] - u[2] * f[1],
                            u[2] * f[0] - u[0] * f[2], u[0] * f[1] - u[1] * f[0])
                        val lateral = -sign(input.toDouble()) * sin(8.0 * rad)
                        val vertical = sin(8.0 * rad)
                        val nose = sqrt(1.0 - lateral * lateral - vertical * vertical)
                        val intent = FixedWingPilotIntent.normalized(
                            f[0] * nose + u[0] * vertical + bodyRight[0] * lateral,
                            f[1] * nose + u[1] * vertical + bodyRight[1] * lateral,
                            f[2] * nose + u[2] * vertical + bodyRight[2] * lateral, 0, input)!!
                        step(m, c, intent, 0, f[0] * 24, f[1] * 24, f[2] * 24)
                        assertTrue(m.aileron * input < 0.0,
                            "yaw=$yaw pitch=$pitch bank=$bank input=$input aileron=${m.aileron}")
                        assertTrue(m.rollRateDegreesPerSecond * input < 0.0)
                        assertTrue(dot(up(m), bodyRight) * input < 0.0,
                            "actual quaternion roll must tilt toward the requested screen side")
                    }
    }

    @Test fun missingScreenSampleReleasesRollWithoutRevivingGravityCapture() {
        val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 110.0)
        val c = FixedWingMouseAimController(h)
        val target = FixedWingPilotIntent.normalized(-sin(15.0 * rad), 0.0, cos(15.0 * rad), 0, 0.8f)!!
        step(m, c, target, 0, 0.0, 0.0, 24.0)
        val previousRate = m.rollRateDegreesPerSecond
        step(m, c, target.copy(screenRollInput = null), 1, 0.0, 0.0, 24.0)
        assertEquals(0.0, m.aileron, 0.0)
        assertTrue(abs(m.rollRateDegreesPerSecond) < abs(previousRate))
        assertTrue(c.angularErrorDegrees > 0.0, "world direction remains retained")
    }

    @Test fun screenTurnCanCounterRollBeforeCrossingItsTargetAndStaySettled() {
        val m = FixedWingFlightModel(h); m.reset(0.0, -h.trimAngleDegrees, 50.0)
        val c = FixedWingMouseAimController(h)
        val pitch = h.trimAngleDegrees * rad
        val target = FixedWingPilotIntent(-sin(15.0 * rad) * cos(pitch), sin(pitch),
            cos(15.0 * rad) * cos(pitch))
        var vx = 0.0; var vy = 0.0; var vz = 24.0
        var counterRolledBeforeTarget = false
        repeat(400) { tick ->
            val headingError = ((15.0 - m.yawDegrees + 540.0) % 360.0) - 180.0
            val screen = (tan(headingError * rad) / (tan(35.0 * rad) * 16.0 / 9.0)).coerceIn(-1.0, 1.0)
            step(m, c, target.copy(screenRollInput = screen.toFloat()), tick.toLong(), vx, vy, vz,
                throttle = if (tick < 14) 1.0 else 0.0, engine = 1.0)
            vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
            if (screen > 0.04 && m.aileron > 0.0) counterRolledBeforeTarget = true
            assertTrue(abs(m.rollDegrees) <= 85.0)
            if (tick > 300) {
                assertTrue(abs(headingError) < 5.0, "settled heading error=$headingError")
                assertTrue(abs(m.rollDegrees) < 12.0)
            }
        }
        assertTrue(counterRolledBeforeTarget, "automatic leveling must not wait for heading overshoot")
    }

    @Test fun screenRollHonorsManualOverrideAndZeroSpeedAuthority() {
        for ((mask, expected) in listOf(4 to -1.0, 8 to 1.0, 12 to 0.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 180.0)
            val c = FixedWingMouseAimController(h)
            val i = toward(m, 5.0).copy(screenRollInput = 1f, manualMask = mask)
            step(m, c, i, 0, 0.0, 0.0, 24.0)
            assertEquals(expected, c.aileronCommand, 0.0)
            assertEquals(expected * min(1.0, h.aileronTravelPerSecond * 0.05), m.aileron, 1e-12)
        }
        val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
        val c = FixedWingMouseAimController(h)
        step(m, c, FixedWingPilotIntent(0.0, 0.0, 1.0, 0, 1f), 0, 0.0, 0.0, 0.0)
        assertEquals(0.0, m.rollRateDegreesPerSecond, 0.0)
        assertEquals(0.0, m.rollDegrees, 0.0)
    }

    @Test fun neutralDirectionalModePreservesTheExistingForceKernel() {
        val neutralHandling=h.copy(wingDropDegreesPerSecond=0.0)
        val a=FixedWingFlightModel(neutralHandling); val b=FixedWingFlightModel(neutralHandling)
        a.reset(0.0,-h.trimAngleDegrees,0.0); b.reset(0.0,-h.trimAngleDegrees,0.0)
        val c=FixedWingMouseAimController(h)
        var vx=0.0; var vy=0.0; var vz=24.0
        repeat(200) { tick ->
            assertTrue(c.update(b,null,false,false,vx,vy,vz,1.0))
            assertTrue(a.step(tick.toLong(),vx,vy,vz,false,false))
            assertTrue(b.step(tick.toLong(),vx,vy,vz,false,false,surfaces=c))
            assertEquals(a.velocityX,b.velocityX,0.0)
            assertEquals(a.velocityY,b.velocityY,0.0)
            assertEquals(a.velocityZ,b.velocityZ,0.0)
            vx=a.velocityX; vy=a.velocityY; vz=a.velocityZ
        }
    }

    @Test fun lowerQuadrantsCrossKnifeEdgeWithBoundedRatesAndRetainElevatorControl() {
        for (side in listOf(-1.0, 1.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(h)
            val length = sqrt(1.0 + 0.18 * 0.18 * 2.0)
            val target = FixedWingPilotIntent(-side * 0.18 / length, -0.18 / length, 1.0 / length,
                0, (side * 0.8).toFloat(), true, inversionRequested = true)
            var vx = 0.0; var vy = 0.0; var vz = 40.0
            var maximumBank = 0.0; var elevatorAfterKnifeEdge = false
            repeat(240) { tick ->
                val previous = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                step(m, c, target, tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 20) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                maximumBank = max(maximumBank, abs(m.rollDegrees))
                if (abs(m.rollDegrees) > 90.0) {
                    // Fine guidance need not request a large elevator angle. Explicit pilot
                    // elevator must still remain fully available beyond knife edge.
                    val manual = FixedWingMouseAimController(h)
                    manual.update(m, target.copy(manualMask = FixedWingPilotIntent.PITCH_UP),
                        true, false, vx, vy, vz, 1.0)
                    assertEquals(1.0, manual.elevatorCommand, 0.0)
                    elevatorAfterKnifeEdge = true
                }
                val current = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                val stepDegrees = 2.0 * acos(abs(previous.dot(current)).coerceIn(0.0, 1.0)) / rad
                assertTrue(stepDegrees < 10.0, "unbounded attitude step=$stepDegrees")
            }
            assertTrue(maximumBank > 100.0, "lower quadrant stopped at bank=$maximumBank side=$side")
            assertTrue(elevatorAfterKnifeEdge, "no banked elevator observation")
        }
    }

    @Test fun horizontalTargetsDoNotInvertFromExistingBanks() {
        for (side in listOf(-1.0, 1.0)) for (bank in listOf(-89.0, -85.0, -80.0, -45.0, 0.0, 45.0, 80.0, 85.0, 89.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, bank)
            val c = FixedWingMouseAimController(h)
            val target = FixedWingPilotIntent(-side * sin(8.0 * rad), 0.0, cos(8.0 * rad),
                0, (side * 0.3).toFloat(), true)
            var vx = 0.0; var vy = 0.0; var vz = 40.0
            var maximumBank = abs(bank)
            repeat(600) { tick ->
                step(m, c, target, tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 20) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                maximumBank = max(maximumBank, abs(m.rollDegrees))
            }
            assertTrue(maximumBank < 90.0, "horizontal side=$side initial=$bank peak=$maximumBank")
            assertTrue(abs(m.rollDegrees) < 1.0, "horizontal turn did not settle: ${m.rollDegrees}")
        }
    }

    @Test fun explicitDownwardPermissionEdgeAcquiresAnUnchangedTarget() {
        for (side in listOf(-1.0, 1.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(h)
            val length = sqrt(1.0 + 0.18 * 0.18 * 2)
            val target = FixedWingPilotIntent(-side * 0.18 / length, -0.18 / length, 1.0 / length,
                0, (side * 0.8).toFloat(), true)
            var vx = 0.0; var vy = 0.0; var vz = 40.0; var maximumBank = 0.0
            repeat(240) { tick ->
                step(m, c, target.copy(inversionRequested = tick >= 5), tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 20) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                maximumBank = max(maximumBank, abs(m.rollDegrees))
            }
            assertTrue(maximumBank > 100.0, "permission edge was lost without a new world ray: $maximumBank")
        }
    }

    @Test fun returningToHorizontalRevokesInversionWithoutAnAttitudeJump() {
        for (side in listOf(-1.0, 1.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(h)
            val length = sqrt(1.0 + 0.18 * 0.18 * 2)
            val lower = FixedWingPilotIntent(-side * 0.18 / length, -0.18 / length, 1.0 / length,
                0, (side * 0.3).toFloat(), true, inversionRequested = true)
            val horizontal = FixedWingPilotIntent(-side * sin(8.0 * rad), 0.0, cos(8.0 * rad),
                0, (side * 0.3).toFloat(), true)
            var vx = 0.0; var vy = 0.0; var vz = 40.0; var maximumBank = 0.0
            repeat(240) { tick ->
                val before = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                step(m, c, if (tick < 10) lower else horizontal, tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 20) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                maximumBank = max(maximumBank, abs(m.rollDegrees))
                val after = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                assertTrue(2 * acos(abs(before.dot(after)).coerceIn(0.0, 1.0)) / rad < 8.0)
            }
            assertTrue(maximumBank < 90.0, "revoked turn still inverted: $maximumBank")
            assertTrue(abs(m.rollDegrees) < 1.0, "revoked turn did not level: ${m.rollDegrees}")
        }
    }

    @Test fun newlyQualifiedVerticalManoeuvreHasEnoughRollAuthorityAfterMouseCurve() {
        // R1 gameplay reached -23.67 degrees but acquired only .0746 physical aileron.
        // A latched manoeuvre must have meaningful authority as soon as it qualifies;
        // the ordinary near-centre mouse curve still applies outside that manoeuvre.
        for (angle in listOf(20.1, 21.0, 23.67, 25.0)) {
            val m=FixedWingFlightModel(h); m.reset(0.0,0.0,0.0)
            val c=FixedWingMouseAimController(h)
            c.update(m,toward(m,-angle).copy(screenRollInput=0F,inversionRequested=true),
                true,false,0.0,0.0,40.0,1.0)
            assertTrue(abs(c.aileronCommand)>0.7,"qualified angle=$angle aileron=${c.aileronCommand}")
            assertTrue(c.elevatorCommand<0.0,"pitch must remain available while rolling")
        }
    }

    @Test fun deepLowerAimCanRollInvertedWithoutHorizontalMouseTravel() {
        for (angle in listOf(45.0, 60.0, 80.0)) for (side in listOf(-1.0, 0.0, 1.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, side * 2.0)
            val c = FixedWingMouseAimController(h)
            val target = FixedWingPilotIntent(0.0, -sin(angle * rad), cos(angle * rad),
                0, 0F, true, inversionRequested = true)
            var vx = 0.0; var vy = 0.0; var vz = 40.0
            var peakBank = 0.0; var positivePull = false
            repeat(400) { tick ->
                val before = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                step(m, c, target, tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 20) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                peakBank = max(peakBank, abs(m.rollDegrees))
                if (abs(m.rollDegrees) > 90.0 && m.elevator > 0.1) positivePull = true
                val after = org.joml.Quaterniond(m.quaternionX, m.quaternionY, m.quaternionZ, m.quaternionW)
                assertTrue(2.0 * acos(abs(before.dot(after)).coerceIn(0.0, 1.0)) / rad < 10.0)
            }
            val error = acos(dot(forward(m), direction(target)).coerceIn(-1.0, 1.0)) / rad
            assertTrue(peakBank > 100.0, "angle=$angle side=$side peakBank=$peakBank error=$error")
            assertTrue(positivePull, "no positive elevator through inverted capture: angle=$angle side=$side")
            assertTrue(error < 3.0, "lower aim did not capture: angle=$angle side=$side error=$error")
        }
    }

    @Test fun gradualVerticalRetargetCanInvertWithScaledJetHandling() {
        val handling=MiG19FixedWingProfile.HANDLING
        for (bank in listOf(-0.02,0.02)) {
            val m=FixedWingFlightModel(handling); m.reset(0.0,0.0,bank)
            val c=FixedWingMouseAimController(handling)
            var vx=0.0; var vy=0.0; var vz=110.0
            var peakBank=0.0; var positivePull=false
            var aim=FixedWingPilotIntent(0.0,0.0,1.0)
            repeat(400) { tick ->
                val angle=30.0*min(tick/16.0,1.0)
                aim=FixedWingPilotIntent(0.0,-sin(angle*rad),cos(angle*rad),0,0F,
                    inversionRequested=angle>8.0)
                c.update(m,aim,true,false,vx,vy,vz,0.25)
                assertTrue(m.step(tick.toLong(),vx,vy,vz,false,true,airDensityRatio=0.25,surfaces=c))
                vx=m.velocityX; vy=m.velocityY; vz=m.velocityZ
                peakBank=max(peakBank,abs(m.rollDegrees))
                if (up(m)[1]<-0.1 && m.elevator>0.1) positivePull=true
            }
            assertTrue(peakBank>100.0,"ramped lower turn stopped at bank=$peakBank")
            assertTrue(positivePull,"ramped lower turn never used inverted positive elevator")
            assertTrue(acos(dot(forward(m),direction(aim)).coerceIn(-1.0,1.0))/rad<3.0)
        }
    }

    @Test fun smallQualifiedDownwardCorrectionsKeepDirectElevatorAndQuietRoll() {
        for (pitch in listOf(2.0, 5.0, 12.0, 18.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(h)
            val target = toward(m, -pitch).copy(screenRollInput = 0F, inversionRequested = true)
            c.update(m, target, true, false, 0.0, 0.0, 40.0, 1.0)
            assertTrue(c.elevatorCommand < 0.0)
            assertEquals(0.0, c.aileronCommand, 1e-9)
        }
    }

    @Test fun alreadyInvertedAircraftKeepsUsefulPositivePitchBeforeLeveling() {
        for (bank in listOf(-180.0, -150.0, 150.0, 180.0)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, bank)
            val c = FixedWingMouseAimController(h)
            val target = toward(m, 30.0).copy(screenRollInput=0F, inversionRequested=true)
            c.update(m, target, true, false, 0.0, 0.0, 40.0, 1.0)
            assertTrue(c.elevatorCommand > 0.5, "inverted positive pull lost at bank=$bank")
            assertTrue(abs(c.aileronCommand) < 0.01, "aligned turn rolled away at bank=$bank")
            var vx=0.0; var vy=0.0; var vz=40.0
            var closest = 30.0
            repeat(60) { tick ->
                step(m, c, target, tick.toLong(), vx, vy, vz, engine=1.0)
                vx=m.velocityX; vy=m.velocityY; vz=m.velocityZ
                closest=min(closest, acos(dot(forward(m), direction(target)).coerceIn(-1.0,1.0))/rad)
            }
            assertTrue(closest < 3.0, "inverted turn did not acquire target at bank=$bank: $closest")
        }
    }

    @Test fun cancellingDeepVerticalManoeuvreReturnsToHorizontalSmoothly() {
        for (bank in listOf(-2.0, 2.0)) for (cancelTick in listOf(10, 30)) {
            val m = FixedWingFlightModel(h); m.reset(0.0, 0.0, bank)
            val c = FixedWingMouseAimController(h)
            val lower = FixedWingPilotIntent(0.0, -sin(60.0*rad), cos(60.0*rad),
                0, 0F, true, inversionRequested=true)
            val level = FixedWingPilotIntent(0.0, 0.0, 1.0, 0, 0F, true)
            var vx=0.0; var vy=0.0; var vz=40.0
            repeat(400) { tick ->
                val before=org.joml.Quaterniond(m.quaternionX,m.quaternionY,m.quaternionZ,m.quaternionW)
                step(m,c,if(tick<cancelTick) lower else level,tick.toLong(),vx,vy,vz,
                    throttle=if(tick<20) 1.0 else 0.0,engine=1.0)
                vx=m.velocityX; vy=m.velocityY; vz=m.velocityZ
                val after=org.joml.Quaterniond(m.quaternionX,m.quaternionY,m.quaternionZ,m.quaternionW)
                assertTrue(2.0*acos(abs(before.dot(after)).coerceIn(0.0,1.0))/rad<10.0)
            }
            assertTrue(abs(m.rollDegrees)<3.0, "cancelTick=$cancelTick bank=$bank roll=${m.rollDegrees}")
            assertTrue(acos(dot(forward(m),direction(level)).coerceIn(-1.0,1.0))/rad<3.0)
        }
    }

    @Test fun blendedRollErrorAlwaysUsesTheShortestArc() {
        val c=FixedWingMouseAimController(h)
        val method=c.javaClass.getDeclaredMethod("blendAngle",Double::class.javaPrimitiveType,
            Double::class.javaPrimitiveType,Double::class.javaPrimitiveType).also{it.isAccessible=true}
        fun wrap(a:Double)=((a+180.0)%360.0+360.0)%360.0-180.0
        fun blend(a:Double,b:Double,w:Double)=method.invoke(c,a,b,w) as Double
        assertEquals(-165.0,blend(170.0,-165.0,1.0),1e-9)
        for(a in -180..180 step 15)for(b in -180..180 step 15) {
            var previous=blend(a.toDouble(),b.toDouble(),0.0)
            for(i in 1..20){
                val value=blend(a.toDouble(),b.toDouble(),i/20.0)
                assertTrue(value>=-180.0 && value<180.0)
                assertTrue(abs(wrap(value-previous))<=9.0+1e-9)
                previous=value
            }
            assertEquals(0.0,wrap(previous-b),1e-9)
        }
    }

    @Test fun bodyPitchRetargetDuringOrdinaryBankCapturesWithoutAnAttitudeJump() {
        for (heading in listOf(-30.0, 30.0)) for (pitchChange in listOf(-12.0, 12.0)) {
            val m = FixedWingFlightModel(h)
            m.reset(0.0, -h.trimAngleDegrees, 0.0)
            val c = FixedWingMouseAimController(h)
            val pitch = h.trimAngleDegrees * rad
            var intent = FixedWingPilotIntent(-sin(heading * rad) * cos(pitch),
                sin(pitch), cos(heading * rad) * cos(pitch))
            var vx = 0.0; var vy = 0.0; var vz = 24.0
            repeat(480) { tick ->
                if (tick == 20) {
                    assertTrue(abs(m.rollDegrees) in 10.0..85.0)
                    intent = toward(m, pitchChange)
                }
                val previousForward = forward(m)
                step(m, c, intent, tick.toLong(), vx, vy, vz,
                    throttle = if (tick < 14) 1.0 else 0.0, engine = 1.0)
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                val attitudeStep = acos(dot(previousForward, forward(m)).coerceIn(-1.0, 1.0)) / rad
                assertTrue(attitudeStep < 5.0, "attitude step=$attitudeStep tick=$tick")
                assertTrue(abs(m.rollDegrees) <= 85.0, "bank=${m.rollDegrees} tick=$tick")
                if (tick >= 260) {
                    val error = acos(dot(forward(m), direction(intent)).coerceIn(-1.0, 1.0)) / rad
                    assertTrue(error < 2.0, "heading=$heading pitchChange=$pitchChange error=$error")
                    assertTrue(abs(m.rollDegrees) < 12.0)
                }
            }
        }
    }
}
