package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.*

class FixedWingEnergyTest {
    private val profile = FixedWingHandlingProfile.GAME_JET

    @Test
    fun progressivePitchAndFullPullStayWithinAttachedFlightAuthority() {
        for (speed in listOf(12.0, 18.0, 24.0, 32.0, 40.0)) {
            val alpha = min(10.0, profile.stallAngleDegrees * (profile.liftReferenceSpeedMps / speed).pow(2))
            val rig = Rig(speed = speed, pitch = -alpha)
            repeat(80) {
                rig.step(pitchTarget = 1.0)
                assertTrue(abs(rig.model.pitchRateDegreesPerSecond) <= profile.gamePitchRateDegreesPerSecond + 1.0E-9)
                assertTrue(abs(rig.model.angleOfAttackDegrees) < 16.0, "speed=$speed tick=$it")
                assertFalse(rig.model.stallActive, "full pull must not outrun lift at speed=$speed tick=$it")
            }
        }
        val gentle = Rig(speed = 32.0, pitch = -3.0)
        var peakRate = 0.0
        repeat(40) {
            gentle.step(pitchTarget = 0.35)
            peakRate = max(peakRate, abs(gentle.model.pitchRateDegreesPerSecond))
        }
        assertTrue(peakRate > 0.0 && peakRate < profile.gamePitchRateDegreesPerSecond * 0.30,
            "partial pitch must remain well below full rate: $peakRate")
    }

    @Test
    fun pitchDemandRespectsAvailableNormalLoad() {
        fun peak(load: Double): Double {
            val rig = Rig(profile.copy(maximumLoadFactor = load), speed = 32.0, pitch = -3.0)
            var peak = 0.0
            repeat(40) {
                rig.step(pitchTarget = 1.0)
                peak = max(peak, abs(rig.model.pitchRateDegreesPerSecond))
                assertFalse(rig.model.stallActive)
            }
            return peak
        }
        assertTrue(peak(1.5) < peak(7.5) * 0.5, "pitch must scale with attainable trajectory curvature")
    }

    @Test
    fun loadingProtectionHasTheSameWorldResponseWhenInverted() {
        for (demand in listOf(-1.0, -0.35, 0.35, 1.0)) {
            val upright = Rig(speed = 32.0, pitch = -3.0)
            val inverted = Rig(speed = 32.0, pitch = -3.0, roll = 180.0)
            repeat(80) {
                upright.step(pitchTarget = demand)
                inverted.step(pitchTarget = -demand)
                assertEquals(upright.vx, inverted.vx, 1.0E-8)
                assertEquals(upright.vy, inverted.vy, 1.0E-8)
                assertEquals(upright.vz, inverted.vz, 1.0E-8)
                assertEquals(upright.model.pitchRateDegreesPerSecond,
                    -inverted.model.pitchRateDegreesPerSecond, 1.0E-8)
                assertEquals(upright.model.stallActive, inverted.model.stallActive)
            }
        }
    }

    @Test
    fun stallProtectionBlocksLoadingButPreservesUnloading() {
        val loading = Rig(speed = 24.0, pitch = -25.0, throttleTicks = 0)
        val unloading = Rig(speed = 24.0, pitch = -25.0, throttleTicks = 0)
        loading.step(pitchTarget = 1.0, engine = 0.0)
        unloading.step(pitchTarget = -1.0, engine = 0.0)
        assertEquals(0.0, loading.model.pitchRateDegreesPerSecond, 0.0)
        assertTrue(unloading.model.pitchRateDegreesPerSecond < 0.0)
        assertTrue(unloading.model.elevator > -1.0, "recovery starts before the actuator reaches full travel")
        assertTrue(unloading.model.angleOfAttackDegrees < loading.model.angleOfAttackDegrees)
        repeat(30) { unloading.step(pitchTarget = -0.6) }
        assertTrue(unloading.model.angleOfAttackDegrees < 10.0, "unloading must escape the initial stall")
    }

    @Test
    fun steepBankRetainsRollControlAndRecoversAfterGivingUpAltitude() {
        for (bank in listOf(-80.0, 80.0)) {
            val cosine = cos(bank * PI / 180.0)
            val alpha = profile.stallAngleDegrees * (profile.liftReferenceSpeedMps / 38.0).pow(2) / cosine
            val rig = Rig(speed = 38.0, pitch = -atan(cosine * tan(alpha * PI / 180.0)) * 180.0 / PI, roll = bank)
            rig.alignInitialAirflow(alpha)
            repeat(100) {
                rig.levelBank(bank, afterburner = true)
                assertFalse(rig.model.stallActive)
                assertTrue(abs(Rig.wrap(rig.model.rollDegrees - bank)) < 3.0)
            }
            assertTrue(rig.height < 490.0, "pitch-limited steep banking cannot hold altitude indefinitely")
            repeat(200) { rig.levelBank(0.0, afterburner = true) }
            assertFalse(rig.model.stallActive)
            assertTrue(abs(rig.model.rollDegrees) < 3.0)
            assertTrue(rig.minimumHeight > 350.0, "rollout must preserve recovery altitude")
            assertTrue(rig.minimumSpeed > 28.0)
        }
    }

    @Test
    fun ordinaryBankAndPullKeepsTheNoseAlignedWithForwardAirflow() {
        for (speed in listOf(24.0, 32.0, 40.0)) for (bank in listOf(-60.0, 60.0)) {
            for (pull in listOf(0.15, 0.35)) {
                val cosine = cos(bank * PI / 180.0)
                val alpha = profile.stallAngleDegrees * (profile.liftReferenceSpeedMps / speed).pow(2) / cosine
                val rig = Rig(speed = speed, roll = bank,
                    pitch = -atan(cosine * tan(alpha * PI / 180.0)) * 180.0 / PI)
                rig.alignInitialAirflow(alpha)
                var peakSideslip = 0.0
                try {
                    repeat(80) {
                        rig.step(pitchTarget = pull)
                        peakSideslip = max(peakSideslip, abs(rig.model.sideslipDegrees))
                        assertFalse(rig.model.stallActive, "ordinary pull stalled at speed=$speed bank=$bank")
                        assertTrue(abs(rig.model.yawRateDegreesPerSecond) <= profile.rudderRateDegreesPerSecond + 1.0E-9)
                    }
                    assertTrue(peakSideslip <= if (speed == 24.0) 3.0 else 2.0,
                        "speed=$speed bank=$bank pull=$pull peak sideslip=$peakSideslip")
                    assertTrue(abs(rig.model.sideslipDegrees) < 0.25, "sustained sideslip must settle")
                    repeat(60) { rig.step() }
                    assertTrue(abs(rig.model.sideslipDegrees) < 0.25, "released controls must remain coordinated")
                } finally { rig.retain("coordinated-pull-$speed-$bank-$pull") }
            }
        }
    }

    @Test
    fun coordinationDoesNotActOutsideAttachedForwardFlight() {
        val isolated = profile.copy(headingStabilityPerSecond = 0.0)
        for (bank in listOf(-60.0, 60.0)) {
            for (speed in listOf(0.0, 5.0, -24.0)) {
                val model = FixedWingFlightModel(isolated)
                model.reset(0.0, 0.0, bank)
                model.step(0, 0.0, 0.0, speed, false, true)
                assertEquals(0.0, model.yawRateDegreesPerSecond, 0.0)
            }
            val highAlpha = FixedWingFlightModel(isolated)
            highAlpha.reset(0.0, -60.0, bank)
            highAlpha.step(0, 0.0, 0.0, 24.0, false, true)
            assertEquals(0.0, highAlpha.yawRateDegreesPerSecond, 0.0, "guard must precede stall latch")
            val grounded = FixedWingFlightModel(isolated)
            grounded.reset(0.0, 0.0, bank)
            grounded.step(0, 0.0, 0.0, 24.0, true, true)
            assertEquals(0.0, grounded.yawRateDegreesPerSecond, 0.0)
        }
    }

    @Test
    fun higherWingLoadSpendsMoreEnergyAtTheSameInitialAirspeed() {
        var previousDragLoss = 0.0
        for (load in listOf(1.0, 2.0, 4.0, 6.0)) {
            val speed = 38.0
            val alpha = load * profile.stallAngleDegrees * (profile.liftReferenceSpeedMps / speed).pow(2)
            val rig = Rig(speed = speed, pitch = -alpha, throttleTicks = 0)
            val initialEnergy = rig.energy()
            rig.step(engine = 0.0)
            val loss = -rig.dragWork
            assertEquals(speed * speed * 0.5, rig.model.preStepKineticEnergyPerKg, 1.0E-9)
            assertTrue(loss > previousDragLoss, "matched airspeed load=$load drag loss=$loss previous=$previousDragLoss")
            assertFalse(rig.model.stallActive)
            assertEquals(rig.energy() - initialEnergy, rig.dragWork + rig.quadratureError, 1.0E-8)
            previousDragLoss = loss
        }
    }

    @Test
    fun automaticReturnUsesSimulationTimeAndHomeIsFaster() {
        val automatic = Rig()
        val centered = Rig()
        automatic.step(pitchTarget = 0.8, rollTarget = -0.6)
        centered.step(pitchTarget = 0.8, rollTarget = -0.6)
        repeat(40) { tick ->
            automatic.step(drift = true)
            centered.step(center = true)
            val elapsed = (tick + 1) * FixedWingFlightModel.DT
            assertEquals(0.8 * exp(-profile.automaticReturnPerSecond * elapsed),
                automatic.model.virtualPitchTarget, 1.0E-12)
            assertEquals(-0.6 * exp(-profile.automaticReturnPerSecond * elapsed),
                automatic.model.virtualRollTarget, 1.0E-12)
            assertEquals(0.0, centered.model.virtualPitchTarget, 0.0)
            if (tick == 0) assertTrue(abs(centered.model.elevator) < abs(automatic.model.elevator))
        }
        val onePacket = FixedWingPilotMouseInput()
        val splitPackets = FixedWingPilotMouseInput()
        val pilot = java.util.UUID(5L, 6L)
        onePacket.offer(pilot, 30.0, -20.0)
        repeat(5) { splitPackets.offer(pilot, 6.0, -4.0) }
        onePacket.consume(pilot)
        splitPackets.consume(pilot)
        assertEquals(onePacket.sampledX, splitPackets.sampledX, 0.0)
        assertEquals(onePacket.sampledY, splitPackets.sampledY, 0.0)
    }

    private class Rig(
        val h: FixedWingHandlingProfile = FixedWingHandlingProfile.GAME_JET,
        speed: Double = 32.0,
        var height: Double = 500.0,
        pitch: Double = 0.0,
        roll: Double = 0.0,
        throttleTicks: Int = 40,
    ) {
        val model = FixedWingFlightModel(h)
        var vx = 0.0; var vy = 0.0; var vz = speed
        var tick = 0L
        var grounded = height == 0.0
        var rollTravel = 0.0
        var headingTravel = 0.0
        var dragWork = 0.0
        var thrustWork = 0.0
        var quadratureError = 0.0
        var maxWorkResidual = 0.0
        var minimumSpeed = speed
        var minimumHeight = height
        var stalls = 0
        val rows = mutableListOf("tick,height,speed,pitch,roll,yaw,aoa,beta,authority,elevator,aileron,rollTravel,dragWork,thrustWork,workResidual,gravityQuadrature")

        init {
            model.reset(0.0, pitch, roll)
            repeat(throttleTicks) {
                check(model.step(tick++, 0.0, 0.0, 0.0, false, true, throttleAxis = 1.0))
            }
        }

        fun speed(): Double = sqrt(vx * vx + vy * vy + vz * vz)
        fun energy(): Double = 0.5 * speed().pow(2) + h.gravityMps2 * height

        fun alignInitialAirflow(alphaDegrees: Double) {
            val qx=model.quaternionX; val qy=model.quaternionY
            val qz=model.quaternionZ; val qw=model.quaternionW
            val up = doubleArrayOf(2*(qx*qy-qz*qw), 1-2*(qx*qx+qz*qz), 2*(qy*qz+qx*qw))
            val forward = doubleArrayOf(2*(qx*qz+qy*qw), 2*(qy*qz-qx*qw), 1-2*(qx*qx+qy*qy))
            val speed = speed(); val alpha = alphaDegrees * PI / 180.0
            vx = speed*(forward[0]*cos(alpha)-up[0]*sin(alpha))
            vy = speed*(forward[1]*cos(alpha)-up[1]*sin(alpha))
            vz = speed*(forward[2]*cos(alpha)-up[2]*sin(alpha))
        }

        fun step(pitchTarget: Double = 0.0, rollTarget: Double = 0.0,
                 keyboard: Double = 0.0, throttle: Double = 0.0, engine: Double = 1.0,
                 afterburner: Boolean = false, brake: Boolean = false,
                 drift: Boolean = false, center: Boolean = false) {
            val decay = exp(-h.automaticReturnPerSecond * FixedWingFlightModel.DT)
            val oldHeading = model.yawDegrees
            val oldHeight = height
            check(model.step(tick++, vx, vy, vz, grounded, true,
                throttleAxis = throttle,
                pitchDelta = if (drift) 0.0 else (pitchTarget-model.virtualPitchTarget*decay)/h.stickSensitivity,
                rollDelta = if (drift) 0.0 else (rollTarget-model.virtualRollTarget*decay)/h.stickSensitivity,
                airbrakeRequested=brake, afterburnerRequested=afterburner,
                recenterRequested=center, engineAvailability=engine, keyboardRollInput=keyboard))
            val work = model.stepThrustWorkPerKg + model.stepGravityWorkPerKg +
                model.stepDragWorkPerKg + model.stepSideWorkPerKg + model.stepLiftWorkPerKg +
                model.stepGroundResistanceWorkPerKg
            val residual = model.postStepKineticEnergyPerKg-model.preStepKineticEnergyPerKg-work
            maxWorkResidual=max(maxWorkResidual,abs(residual))
            assertTrue(abs(residual)<1.0E-8,"unaccounted force work tick=$tick residual=$residual")
            assertTrue(model.stepDragWorkPerKg<=1.0E-9 && model.stepSideWorkPerKg<=1.0E-9,
                "passive aerodynamic drag must not add energy")
            assertTrue(abs(model.stepLiftWorkPerKg)<1.0E-8,"lift must not change kinetic energy")
            vx=model.velocityX; vy=model.velocityY; vz=model.velocityZ
            height += vy * FixedWingFlightModel.DT
            grounded = height <= 0.0 && vy < 0.0
            if (grounded) {height=0.0;vy=0.0}
            val quadrature=model.stepGravityWorkPerKg+h.gravityMps2*(height-oldHeight)
            quadratureError+=quadrature
            rollTravel+=model.rollRateDegreesPerSecond*FixedWingFlightModel.DT
            headingTravel+=wrap(model.yawDegrees-oldHeading)
            dragWork+=model.stepDragWorkPerKg+model.stepSideWorkPerKg
            thrustWork+=model.stepThrustWorkPerKg
            minimumSpeed=min(minimumSpeed,speed());minimumHeight=min(minimumHeight,height)
            if(model.stallActive)stalls++
            rows += listOf(tick,height,speed(),model.pitchDegrees,model.rollDegrees,model.yawDegrees,
                model.angleOfAttackDegrees,model.sideslipDegrees,model.controlEffectiveness,
                model.elevator,model.aileron,rollTravel,dragWork,thrustWork,residual,quadrature).joinToString(",")
        }

        /** Pilot attitude controller: convert desired Euler rates into local elevator/aileron rates. */
        fun attitude(pitch: Double, roll: Double, engine: Double=1.0, afterburner: Boolean=false) {
            val bank=-model.rollDegrees*PI/180.0
            val elevation=-model.pitchDegrees*PI/180.0
            val cosine=cos(bank)
            check(abs(cosine)>0.08) {"Use normalized controls at knife edge"}
            val elevatorRate=((model.pitchDegrees-pitch)*3.0+
                model.yawRateDegreesPerSecond*sin(bank))/cosine
            val aileronRate=wrap(model.rollDegrees-roll)*3.0-tan(elevation)*
                (model.pitchRateDegreesPerSecond*sin(bank)+model.yawRateDegreesPerSecond*cosine)
            val authority=model.controlEffectiveness.coerceAtLeast(0.05)
            val demand = (elevatorRate/(h.gamePitchRateDegreesPerSecond*authority)).coerceIn(-1.0,1.0)
            val linear = h.pitchResponseLinearFraction
            val magnitude = if(linear == 1.0) abs(demand) else
                (sqrt(linear*linear+4*(1-linear)*abs(demand))-linear)/(2*(1-linear))
            step(surfaceTarget(sign(demand)*magnitude),
                surfaceTarget(aileronRate/(h.gameRollRateDegreesPerSecond*authority)),
                engine=engine, afterburner=afterburner)
        }

        fun levelBank(roll: Double, referenceHeight: Double=500.0, engine: Double=1.0,
                      afterburner: Boolean=false) {
            val cosine=cos(roll*PI/180.0)
            val alpha=(h.stallAngleDegrees*(h.liftReferenceSpeedMps/speed().coerceAtLeast(6.0)).pow(2)/cosine)
                .coerceIn(-h.stallAngleDegrees*0.98,h.stallAngleDegrees*0.98)
            val trim=atan(cosine*tan(alpha*PI/180.0))
            val climb=((referenceHeight-height)*0.35).coerceIn(-5.0,5.0)
            val gamma=asin((climb/speed().coerceAtLeast(6.0)).coerceIn(-0.8,0.8))
            attitude(-(trim+gamma)*180.0/PI,roll,engine,afterburner)
        }

        private fun surfaceTarget(value: Double): Double {
            val bounded=value.coerceIn(-1.0,1.0)
            return if(abs(bounded)<1.0E-9)0.0 else sign(bounded)*(h.stickDeadzone+(1-h.stickDeadzone)*abs(bounded))
        }

        fun retain(label: String) {
            val directory=System.getProperty("bvp.flight.evidence") ?: return
            File(directory,"$label.csv").writeText(rows.joinToString("\n")+"\n")
        }

        companion object {
            fun wrap(value: Double): Double=((value+540.0)%360.0)-180.0
        }
    }

    @Test
    fun isolatedOverspeedAndFullRollCannotEraseWorldMomentum() {
        val isolated=profile.copy(gravityMps2=1.0E-9,dryAccelerationMps2=0.0,
            parasiteDragPerMetre=0.0,inducedDragMps2=0.0,airbrakeDragPerMetre=0.0,
            sideDragPerMetre=0.0,overspeedDragPerMetre=0.0,wingDropDegreesPerSecond=0.0,
            overspeedResponsePerSecond=0.0,headingStabilityPerSecond=0.0)
        for(direction in listOf(-1.0,1.0)) {
            val rig=Rig(isolated,speed=40.0,throttleTicks=0)
            repeat(400){rig.step(keyboard=direction,engine=0.0)}
            assertTrue(abs(rig.rollTravel)>1300.0,"multiple full body rolls required")
            // rolling bleeds some energy (maneuver drag) but never turns the momentum sideways
            assertEquals(0.0,rig.vx,1.0E-7)
            assertEquals(rig.speed(),rig.vz,1.0E-7)
            assertTrue(rig.speed() > 20.0 && rig.speed() <= 40.0 + 1.0E-9)
            assertTrue(rig.dragWork <= 0.0)
            rig.retain("isolated-roll-$direction")
        }
    }

    @Test
    fun invertedAndUprightAirframesShareTheSameWorldTrajectory() {
        for(speed in listOf(18.0,24.0,32.0,40.0)) {
            val upright=Rig(speed=speed,pitch=-profile.trimAngleDegrees)
            val inverted=Rig(speed=speed,pitch=-profile.trimAngleDegrees,roll=180.0)
            repeat(300) {
                upright.step();inverted.step()
                assertEquals(upright.vx,inverted.vx,1.0E-8,"x at speed=$speed tick=$it")
                assertEquals(upright.vy,inverted.vy,1.0E-8,"y at speed=$speed tick=$it")
                assertEquals(upright.vz,inverted.vz,1.0E-8,"z at speed=$speed tick=$it")
                assertEquals(upright.height,inverted.height,1.0E-8)
            }
            assertEquals(upright.stalls,inverted.stalls)
        }
    }

    @Test
    fun fullRollsAndReversalsRetainUsefulSpeedAndReleaseAngularRate() {
        for(direction in listOf(-1.0,1.0)) {
            // Slower rolls now take long enough to reach the old fixture's ground plane.
            // Keep this airborne release test separate from landing-wheel stabilization.
            val rig=Rig(speed=36.0,pitch=-2.5,height=2000.0)
            try {
                repeat(125){rig.step(keyboard=direction)}
                assertTrue(abs(rig.rollTravel)>360.0,"full roll travel=${rig.rollTravel}")
                val firstTravel=rig.rollTravel
                repeat(134){rig.step(keyboard=-direction)}
                assertTrue((rig.rollTravel-firstTravel)*direction < -360.0)
                repeat(30){rig.step()}
                assertFalse(rig.grounded, "roll-release fixture must remain airborne")
                assertTrue(rig.minimumSpeed>24.0,"minimum roll speed=${rig.minimumSpeed}")
                assertTrue(rig.speed()<=profile.hardSpeedLimitMps+1e-9)
                val buffet = FixedWingSpeedEffects.buffetDegrees(rig.speed(), profile)
                assertTrue(abs(rig.model.rollRateDegreesPerSecond) < buffet + 0.05,
                    "released roll rate=${rig.model.rollRateDegreesPerSecond} speed=${rig.speed()} buffet=$buffet aileron=${rig.model.aileron} stall=${rig.model.stallActive}/${rig.model.stallSeverity} target=${rig.model.virtualRollTarget}")
            } finally {rig.retain("powered-roll-reversal-$direction")}
        }
    }

    @Test
    fun controlledBanksStayStableAcrossTheFlightEnvelope() {
        for(bank in listOf(-60.0,-45.0,-30.0,-15.0,0.0,15.0,30.0,45.0,60.0,180.0)) {
            val cosine=cos(bank*PI/180.0)
            val alpha=profile.stallAngleDegrees*(profile.liftReferenceSpeedMps/38.0).pow(2)/cosine
            val pitch=-atan(cosine*tan(alpha*PI/180.0))*180.0/PI
            val rig=Rig(speed=38.0,pitch=pitch,roll=bank)
            rig.alignInitialAirflow(alpha)
            try {
                repeat(400) {
                    rig.levelBank(bank)
                    assertFalse(rig.grounded,"bank=$bank tick=$it")
                    if(it>100) {
                        assertFalse(rig.model.stallActive,"ordinary controlled bank=$bank tick=$it")
                        assertTrue(abs(Rig.wrap(rig.model.rollDegrees-bank))<3.0,
                            "bank=$bank actual=${rig.model.rollDegrees} tick=$it")
                    }
                }
                assertTrue(rig.minimumSpeed>=28.0,"bank=$bank minSpeed=${rig.minimumSpeed}")
                assertTrue(rig.speed()<=profile.hardSpeedLimitMps+1e-9)
                assertTrue(abs(rig.height-500.0)<25.0,"bank=$bank height=${rig.height}")
                if(abs(bank) in 15.0..60.0)assertTrue(abs(rig.headingTravel)>20.0,
                    "bank must actually turn the flight path: bank=$bank heading=${rig.headingTravel}")
            } finally {rig.retain("controlled-bank-$bank")}
        }
    }

    @Test
    fun idleLoadedTurnSpendsMoreEnergyThanWingsLevelFlight() {
        fun coast(bank: Double): Rig {
            val cosine=cos(bank*PI/180.0)
            val alpha=profile.stallAngleDegrees*(profile.liftReferenceSpeedMps/32.0).pow(2)/cosine
            val rig=Rig(speed=32.0,pitch=-atan(cosine*tan(alpha*PI/180.0))*180.0/PI,roll=bank,throttleTicks=0)
            rig.alignInitialAirflow(alpha)
            repeat(60){rig.levelBank(bank,engine=0.0)}
            rig.retain("idle-energy-bank-$bank")
            return rig
        }
        val level=coast(0.0)
        val left=coast(-60.0)
        val right=coast(60.0)
        assertTrue(left.energy()<level.energy()-2.0,"loaded turn must spend additional energy")
        assertEquals(left.energy(),right.energy(),1.0E-6)
        assertTrue(left.dragWork<0.0 && level.dragWork<0.0)
        assertEquals(0.0,left.thrustWork,1.0E-9)
    }

    @Test
    fun dryAndAfterburnerRecoverSpeedWithoutUnboundedWorldTravel() {
        fun accelerate(afterburner: Boolean): Pair<Int,Rig> {
            val rig=Rig(speed=24.0,pitch=-profile.trimAngleDegrees)
            var recovery=-1
            repeat(600) {
                rig.levelBank(0.0,afterburner=afterburner)
                if(rig.speed()>=32.0&&recovery<0)recovery=it
            }
            rig.retain("propulsion-$afterburner")
            assertTrue(recovery in 0..199,"24 -> 32 m/s recovery=$recovery ticks")
            assertTrue(rig.speed()<=profile.hardSpeedLimitMps+1e-9)
            return recovery to rig
        }
        val dry=accelerate(false);val boost=accelerate(true)
        assertTrue(boost.first<dry.first,"afterburner must recover speed sooner")
        assertTrue(boost.second.speed()>dry.second.speed())
    }

    @Test
    fun uprightAndInvertedPullPushAreOppositeWorldResponses() {
        for(inverted in listOf(false,true)) {
            val pull=Rig(speed=30.0,roll=if(inverted)180.0 else 0.0,throttleTicks=0)
            val push=Rig(speed=30.0,roll=if(inverted)180.0 else 0.0,throttleTicks=0)
            repeat(20){pull.step(pitchTarget=0.15,engine=0.0);push.step(pitchTarget=-0.15,engine=0.0)}
            val difference=pull.height-push.height
            assertTrue(if(inverted)difference < -0.1 else difference > 0.1,
                "body pitch must respect inversion: inverted=$inverted difference=$difference")
        }
    }

    @Test
    fun steepClimbAndDiveExchangeAltitudeForSpeedWithAccountedWork() {
        for(angle in listOf(-60.0,-30.0,30.0,60.0)) {
            val rig=Rig(speed=20.0,pitch=-angle,throttleTicks=0,height=1000.0)
            rig.alignInitialAirflow(0.0)
            val energy=rig.energy()
            repeat(20){rig.step(engine=0.0)}
            assertTrue(if(angle>0)rig.height>1000.0 else rig.height<1000.0)
            assertTrue(if(angle>0)rig.speed()<20.0 else rig.speed()>20.0)
            assertTrue(rig.energy()<energy+0.5)
            assertEquals(rig.energy()-energy,rig.dragWork+rig.quadratureError,1.0E-7)
        }
        val dive=Rig(speed=20.0,pitch=70.0,height=1500.0)
        dive.alignInitialAirflow(0.0)
        repeat(400){dive.step(afterburner=true)}
        assertTrue(dive.speed()>20.0,"powered dive must gain speed: ${dive.speed()}")
        assertTrue(dive.speed()<=profile.hardSpeedLimitMps+1e-9,"hard cap must bound the dive")
        dive.retain("powered-steep-dive")
    }

    @Test
    fun forceWorkBalancesAcrossAdverseInitialVectorsAndAttitudes() {
        for(pitch in listOf(-89.5,-45.0,0.0,45.0,89.5))for(roll in listOf(-180.0,-90.0,0.0,90.0,180.0)) {
            val rig=Rig(speed=30.0,pitch=pitch,roll=roll,height=1500.0)
            rig.vx=12.0;rig.vy=-9.0;rig.vz=-20.0
            repeat(100){tick->rig.step(pitchTarget=if(tick<50)0.1 else -0.1,
                rollTarget=if(tick<50)0.2 else -0.2,afterburner=true)}
            assertTrue(rig.speed()<=profile.hardSpeedLimitMps+1e-9)
            val m=rig.model
            assertEquals(1.0,m.quaternionX.pow(2)+m.quaternionY.pow(2)+m.quaternionZ.pow(2)+m.quaternionW.pow(2),1.0E-12)
        }
    }
}
