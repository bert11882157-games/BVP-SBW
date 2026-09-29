package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.sign

class FixedWingFlightModelTest {
    private val handling = FixedWingHandlingProfile.GAME_JET

    /** Only the test collision boundary clamps floor penetration; the model never does. */
    private class Rig(
        pitch: Double = 0.0,
        roll: Double = 0.0,
        speed: Double = 24.0,
        height: Double = 100.0,
        warmThrottleTicks: Int = 0,
    ) {
        val model = FixedWingFlightModel()
        var tick = 0L
        var x = 0.0
        var y = height
        var z = 0.0
        var vx = 0.0
        var vy = 0.0
        var vz = speed
        var grounded = height == 0.0

        init {
            model.reset(0.0, pitch, roll)
            // Establish an engine initial condition without fabricating a test-only model setter.
            repeat(warmThrottleTicks) {
                assertTrue(model.step(
                    serverTick = tick++,
                    inputVelocityX = 0.0,
                    inputVelocityY = 0.0,
                    inputVelocityZ = 0.0,
                    grounded = false,
                    controlsEnabled = true,
                    throttleAxis = 1.0,
                ))
            }
        }

        fun speed(): Double = sqrt(vx * vx + vy * vy + vz * vz)
        fun energy(): Double = 0.5 * speed() * speed() + 9.80665 * y

        fun hold(pitchTarget: Double = 0.0, rollTarget: Double = 0.0, engine: Double = 1.0) {
            val profile = model.handling
            val decay = exp(-profile.automaticReturnPerSecond * FixedWingFlightModel.DT)
            step(pitch = (pitchTarget - model.virtualPitchTarget * decay) / profile.stickSensitivity,
                roll = (rollTarget - model.virtualRollTarget * decay) / profile.stickSensitivity,
                engine = engine)
        }

        fun step(
            throttle: Double = 0.0,
            pitch: Double = 0.0,
            roll: Double = 0.0,
            rudder: Double = 0.0,
            brake: Boolean = false,
            afterburner: Boolean = false,
            center: Boolean = false,
            engine: Double = 1.0,
            enabled: Boolean = true,
        ) {
            assertTrue(model.step(
                serverTick = tick++,
                inputVelocityX = vx,
                inputVelocityY = vy,
                inputVelocityZ = vz,
                grounded = grounded,
                controlsEnabled = enabled,
                throttleAxis = throttle,
                pitchDelta = pitch,
                rollDelta = roll,
                rudderInput = rudder,
                airbrakeRequested = brake,
                afterburnerRequested = afterburner,
                recenterRequested = center,
                engineAvailability = engine,
            ))
            vx = model.velocityX
            vy = model.velocityY
            vz = model.velocityZ
            x += vx * FixedWingFlightModel.DT
            z += vz * FixedWingFlightModel.DT
            val nextY = y + vy * FixedWingFlightModel.DT
            grounded = nextY <= 0.0 && vy < 0.0
            if (grounded) {
                y = 0.0
                vy = 0.0
            } else {
                y = nextY
            }
            assertTrue(x.isFinite() && y.isFinite() && z.isFinite())
            assertTrue(model.yawDegrees.isFinite() && model.pitchDegrees.isFinite() &&
                model.rollDegrees.isFinite())
            assertTrue(model.elevator in -1.0..1.0 && model.aileron in -1.0..1.0)
        }
    }

    @Test
    fun runwayContactDoesNotAlternateOrProduceWingDrop() {
        val rig = Rig(speed = 0.0, height = 0.0)
        repeat(300) {
            rig.step(throttle = 1.0, center = true)
            assertTrue(rig.grounded, "tick=$it")
            assertEquals(0.0, rig.y, 1.0E-12)
            assertTrue(rig.model.velocityY < 0.0, "collision must receive downward demand")
            assertFalse(rig.model.stallActive)
            assertEquals(0.0, rig.model.rollDegrees, 1.0E-9)
        }
        assertTrue(rig.speed() > 20.0)
    }

    @Test
    fun takeoffRequiresRunAndElevatorThenActuallyLeavesCollisionFloor() {
        val rig = Rig(speed = 0.0, height = 0.0)
        var firstLiftoffDistance = Double.NaN
        repeat(400) {
            val rotation = rig.speed() >= 16.0 && rig.y < 2.0
            val wasGrounded = rig.grounded
            rig.step(
                throttle = 1.0,
                pitch = if (rotation) 8.0 else 0.0,
                center = rig.y >= 2.0,
            )
            if (wasGrounded) assertFalse(rig.model.stallActive)
            if (rig.y > 0.1 && firstLiftoffDistance.isNaN()) {
                firstLiftoffDistance = sqrt(rig.x * rig.x + rig.z * rig.z)
            }
        }
        assertTrue(firstLiftoffDistance in 30.0..150.0, "distance=$firstLiftoffDistance")
        assertTrue(rig.y > 5.0, "altitude=${rig.y}")
        assertTrue(rig.speed() in 15.0..handling.hardSpeedLimitMps)
    }

    @Test
    fun neutralStickAndPartialThrottleTrimWithoutVelocitySnapping() {
        val rig = Rig(
            pitch = -handling.trimAngleDegrees,
            warmThrottleTicks = 10,
        )
        repeat(400) { rig.step() }
        assertTrue(abs(rig.y - 100.0) < 8.0, "altitude=${rig.y}")
        assertTrue(rig.speed() in 22.0..26.0, "trim speed=${rig.speed()}")
        assertEquals(0.25, rig.model.throttle, 1.0E-9)
        assertFalse(rig.model.stallActive)
    }

    @Test
    fun climbTradesSpeedForHeightAndDiveReturnsHeightToSpeed() {
        val radians = 20.0 * PI / 180.0
        val climb = Rig(pitch = -20.0)
        val dive = Rig(pitch = 20.0)
        climb.vy = 24.0 * sin(radians)
        climb.vz = 24.0 * cos(radians)
        dive.vy = -24.0 * sin(radians)
        dive.vz = 24.0 * cos(radians)
        val climbEnergy = climb.energy()
        val diveEnergy = dive.energy()
        repeat(20) {
            climb.step(engine = 0.0)
            dive.step(engine = 0.0)
        }
        assertTrue(climb.y > 100.0 && climb.speed() < 24.0)
        assertTrue(dive.y < 100.0 && dive.speed() > 24.0)
        assertTrue(climb.energy() < climbEnergy)
        assertTrue(dive.energy() < diveEnergy)
    }

    @Test
    fun bankChangesWorldVelocityWithoutRudderOrFlatYawThrust() {
        val rig = Rig(
            pitch = -handling.trimAngleDegrees,
            roll = 30.0,
            warmThrottleTicks = 26,
        )
        repeat(100) { rig.step() }
        assertTrue(abs(rig.model.yawDegrees) > 20.0)
        assertTrue(abs(rig.vx) > 3.0)
        assertTrue(abs(rig.model.rollDegrees - 30.0) < 10.0)
    }

    @Test
    fun highAngleStallDropsWingAndRecoversOnlyAfterHysteresis() {
        val rig = Rig(pitch = -35.0, speed = 22.0, height = 200.0, warmThrottleTicks = 40)
        rig.step()
        assertTrue(rig.model.stallActive)
        var largestBank = 0.0
        var recovered = false
        repeat(300) { tick ->
            rig.step(pitch = if (tick < 20) -5.0 else 0.0, center = tick >= 20)
            largestBank = max(largestBank, abs(rig.model.rollDegrees))
            if (!rig.model.stallActive) recovered = true
        }
        assertTrue(largestBank > 0.1)
        assertTrue(recovered)
        assertFalse(rig.model.stallActive)
        assertTrue(rig.y > 0.0)
    }

    @Test
    fun engineFailureRemovesThrustButNotGlideOrAerodynamicControls() {
        val rig = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
        rig.step(pitch = 20.0, roll = 20.0, afterburner = true, engine = 0.0)
        repeat(19) { rig.hold(pitchTarget = 0.2, rollTarget = 0.2, engine = 0.0) }
        assertEquals(0.0, rig.model.thrustAccelerationMps2, 0.0)
        assertFalse(rig.model.afterburnerActive)
        assertTrue(rig.speed() > 15.0)
        assertTrue(rig.model.elevator > 0.1 && rig.model.aileron > 0.1)
        assertTrue(abs(rig.model.rollDegrees) > 1.0)
    }

    @Test
    fun damageScalesThrustAndAfterburnerAndAirbrakeAreIndependent() {
        val dry = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
        val boost = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
        val brake = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 0)
        val damaged = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
        damaged.step(engine = 0.5)
        assertEquals(handling.gameDryAccelerationMps2 * 0.5,
            damaged.model.thrustAccelerationMps2, 1.0E-9)
        repeat(100) {
            dry.step()
            boost.step(afterburner = true)
            brake.step(brake = true)
        }
        assertTrue(boost.speed() > dry.speed())
        assertTrue(brake.speed() < dry.speed())
        assertTrue(brake.model.airbrake > 0.99)
        assertTrue(boost.model.afterburnerActive)
    }

    @Test
    fun landingUsesCollisionContactAndCannotPumpOrReverseUnderBraking() {
        val rig = Rig(speed = 16.0, height = 1.0)
        rig.vy = -1.0
        var touched = false
        repeat(500) {
            rig.step(brake = true, center = true, engine = 0.0)
            if (rig.grounded) touched = true
            if (touched) {
                assertTrue(rig.grounded)
                assertEquals(0.0, rig.y, 1.0E-12)
            }
            assertTrue(rig.vz >= -1.0E-9)
        }
        assertTrue(touched)
        assertTrue(rig.speed() < 0.1)
    }

    @Test
    fun overspeedResponseIsBoundedRatherThanAnInstantVelocityCap() {
        val rig = Rig(speed = 50.0, height = 200.0)
        rig.step(engine = 0.0)
        assertTrue(rig.speed() > handling.maximumSpeedMps)
        assertTrue(rig.speed() < 50.0)
        repeat(199) { rig.step(engine = 0.0) }
        assertTrue(rig.speed() < 40.0)
    }

    @Test
    fun smallDeltasAccumulateThenAutomaticReturnAndExplicitRecenterRelease() {
        val rig = Rig(speed = 24.0)
        repeat(60) { rig.step(pitch = 1.0, roll = 1.0) }
        val heldElevator = rig.model.elevator
        assertTrue(heldElevator > 0.0)
        repeat(20) { rig.step() }
        assertTrue(rig.model.elevator < heldElevator * 0.4)
        repeat(30) { rig.step(center = true) }
        assertEquals(0.0, rig.model.elevator, 1.0E-9)
        assertEquals(0.0, rig.model.aileron, 1.0E-9)
        rig.step(throttle = 1.0, pitch = 10.0)
        rig.model.resetControls()
        assertEquals(0.0, rig.model.throttle, 0.0)
        assertEquals(0.0, rig.model.elevator, 0.0)
    }

    @Test
    fun invalidInputFailsClosedWithOneFiniteGravityDemand() {
        val model = FixedWingFlightModel()
        model.reset(0.0, 0.0, 0.0)
        assertFalse(model.step(1L, Double.NaN, 0.0, 0.0, false, true))
        assertEquals(0.0, model.velocityX, 0.0)
        assertEquals(-handling.gravityMps2 * FixedWingFlightModel.DT, model.velocityY, 0.0)
        assertEquals(0.0, model.velocityZ, 0.0)
        assertEquals(0.0, model.throttle, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            handling.copy(gravityMps2 = Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            handling.copy(recoverySpeedMps = 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            handling.copy(minimumControlSpeedMps = handling.liftReferenceSpeedMps)
        }
    }

    @Test
    fun parkedSurfaceDeflectionsCannotRotateTheAirframe() {
        val rig = Rig(speed = 0.0, height = 0.0)
        repeat(100) {
            rig.step(pitch = 10.0, roll = 10.0, rudder = 1.0)
            assertEquals(0.0, rig.speed(), 1.0E-12)
            assertEquals(0.0, rig.model.pitchDegrees, 1.0E-12)
            assertEquals(0.0, rig.model.rollDegrees, 1.0E-12)
            assertEquals(0.0, rig.model.yawDegrees, 1.0E-12)
            assertEquals(0.0, rig.model.controlEffectiveness, 0.0)
        }
        assertTrue(rig.model.elevator > 0.99 && rig.model.aileron > 0.99)
    }

    /**
     * Pins the rudder sense the rig animator relies on (rudder surfaces, nose-wheel steering): positive rudder yaws
     * the nose toward +x, so Minecraft yaw falls (a left turn as seen by the pilot).
     */
    @Test
    fun positiveRudderYawsTheNoseTowardPlusX() {
        val taxi = Rig(speed = 3.0, height = 0.0)
        repeat(20) { taxi.step(rudder = 1.0) }
        assertTrue(taxi.model.yawDegrees < -0.1, "yaw=${taxi.model.yawDegrees}")
    }

    @Test
    fun surfaceAuthorityStartsSmoothlyAtMinimumAirspeed() {
        var previous = -1.0
        for (speed in listOf(0.0, 3.0, 5.9, 6.0, 6.01, 10.0, 14.0, 16.0, 20.0, 24.0)) {
            val model = FixedWingFlightModel()
            model.reset(0.0, 0.0, 0.0)
            assertTrue(model.step(0, 0.0, 0.0, speed, false, true,
                pitchDelta = 100.0, rollDelta = 100.0, rudderInput = 1.0))
            val authority = model.controlEffectiveness
            assertTrue(authority >= previous, "speed=$speed authority=$authority previous=$previous")
            if (speed <= handling.minimumControlSpeedMps) {
                assertEquals(0.0, authority, 0.0)
                assertEquals(0.0, model.pitchDegrees, 0.0)
                assertEquals(0.0, model.rollDegrees, 0.0)
                assertEquals(0.0, model.yawDegrees, 0.0)
            } else if (speed == 6.01) {
                assertTrue(authority > 0.0 && authority < 0.000001)
            }
            previous = authority
        }
        assertEquals(1.0, previous, 0.0)
        val taxi = Rig(speed = 3.0, height = 0.0)
        repeat(20) { taxi.step(rudder = 1.0) }
        assertTrue(abs(taxi.model.yawDegrees) > 0.1, "wheel steering remains usable")
        assertEquals(0.0, taxi.model.controlEffectiveness, 0.0)
    }

    @Test
    fun twentyFiveMousePixelsGiveSmallProportionalBankAndCorrectNoseUp() {
        for (direction in listOf(-1.0, 1.0)) {
            val rig = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
            // Default client scaling converts 25 physical pixels into ten packet units.
            rig.step(roll = direction * 10.0)
            repeat(19) { rig.step() }
            assertTrue(abs(rig.model.rollDegrees) in 1.0..6.0,
                "25-pixel bank=${rig.model.rollDegrees}")
            assertTrue(rig.model.rollDegrees * direction < 0.0)
            assertEquals(0.0, rig.model.aileron, 1.0E-9, "automatic return should release this small input")
        }
        val noseUp = Rig(pitch = -handling.trimAngleDegrees, warmThrottleTicks = 40)
        noseUp.step(pitch = 10.0)
        repeat(19) { noseUp.step() }
        assertTrue(noseUp.model.pitchDegrees < -handling.trimAngleDegrees)
        assertEquals(0.0, noseUp.model.elevator, 1.0E-9)
    }

    @Test
    fun modestStickBankReleasesBodyRollWithoutGrowingAngularMomentum() {
        for (target in listOf(30.0, 45.0, 60.0)) {
            for (direction in listOf(-1.0, 1.0)) {
                val rig = Rig(pitch = -handling.trimAngleDegrees,
                    height = 500.0, warmThrottleTicks = 40)
                var turnTicks = 0
                while (abs(rig.model.rollDegrees) < target && turnTicks++ < 400) {
                    rig.hold(rollTarget = direction * 0.15)
                }
                assertTrue(turnTicks < 400, "bank target=$target direction=$direction")
                val releasedRate = abs(rig.model.rollRateDegreesPerSecond)
                repeat(20) { rig.hold() }
                assertTrue(abs(rig.model.rollRateDegreesPerSecond) < releasedRate * 0.01,
                    "roll damping must release the admitted body roll command")
                assertEquals(0.0, rig.model.aileron, 1.0E-9)
                repeat(40) {
                    rig.hold()
                    assertTrue(abs(rig.model.rollRateDegreesPerSecond) < 0.15,
                        "uncommanded body roll bank=$target tick=$it")
                }
            }
        }
    }

    @Test
    fun ordinaryBankedStallsRecoverAtModestAltitudeWithoutRelapse() {
        for (bank in listOf(-30.0, 0.0, 30.0)) {
            verifyStallRecovery(bank, -25.0, 17.0, 60.0, 5, -0.6, 120, 40.0, 500,
                requireAltitudeCost = false)
        }
    }

    @Test
    fun reducedPowerStallRecoverySpendsAltitudeWithoutLockingControls() {
        for (bank in listOf(-30.0, 0.0, 30.0)) {
            verifyStallRecovery(bank, -25.0, 17.0, 60.0, 5, -0.6, 120, 40.0, 500,
                engine = 0.5 / 1.6)
        }
    }

    @Test
    fun deepBankedStallsRecoverThroughAirflowAtAnAltitudeCost() {
        for (bank in listOf(-60.0, 60.0)) {
            verifyStallRecovery(bank, -60.0, 12.0, 200.0, 20, -0.6, 220, 30.0, 800)
        }
    }

    private fun verifyStallRecovery(
        bank: Double, pitch: Double, speed: Double, altitude: Double,
        neutralTicks: Int, unload: Double, recoveryLimit: Int, minimumAltitude: Double,
        duration: Int, engine: Double = 1.0, requireAltitudeCost: Boolean = true,
    ) {
        // Horizontal initial velocity deliberately disagrees with the raised nose.
        val rig = Rig(pitch = pitch, roll = bank, speed = speed, height = altitude)
        var recovering = true
        var controlledRecovery = -1
        var minimumHeight = altitude
        var sawStall = false
        repeat(duration) { tick ->
            if (tick <= neutralTicks) {
                rig.step(throttle = 1.0, center = tick == neutralTicks, engine = engine)
            } else {
                if (!rig.model.stallActive && abs(rig.model.angleOfAttackDegrees) <= 10.0 &&
                    rig.speed() >= 16.0) recovering = false
                var pitchTarget = if (recovering) unload else {
                    val desiredRate = ((rig.model.pitchDegrees + handling.trimAngleDegrees) * 2.0 -
                        rig.model.pitchRateDegreesPerSecond * 0.8).coerceIn(-25.0, 25.0)
                    val demand = (desiredRate / (handling.pitchRateDegreesPerSecond *
                        rig.model.controlEffectiveness.coerceAtLeast(0.05))).coerceIn(-1.0, 1.0)
                    val linear = handling.pitchResponseLinearFraction
                    val magnitude = if (linear == 1.0) abs(demand) else
                        (sqrt(linear * linear + 4 * (1 - linear) * abs(demand)) - linear) / (2 * (1 - linear))
                    sign(demand) * (handling.stickDeadzone + (1 - handling.stickDeadzone) * magnitude)
                }
                if (rig.model.angleOfAttackDegrees > 12.0 && pitchTarget > 0.0) pitchTarget = 0.0
                val rollTarget = (rig.model.rollDegrees / 60.0 -
                    rig.model.rollRateDegreesPerSecond / 100.0).coerceIn(-0.5, 0.5)
                rig.step(throttle = 1.0, engine = engine,
                    pitch = (pitchTarget - rig.model.virtualPitchTarget *
                        exp(-handling.automaticReturnPerSecond * FixedWingFlightModel.DT)) / handling.stickSensitivity,
                    roll = (rollTarget - rig.model.virtualRollTarget *
                        exp(-handling.automaticReturnPerSecond * FixedWingFlightModel.DT)) / handling.stickSensitivity)
            }
            sawStall = sawStall || rig.model.stallActive
            minimumHeight = kotlin.math.min(minimumHeight, rig.y)
            if (!recovering && !rig.model.stallActive && abs(rig.model.rollDegrees) < 10.0 &&
                abs(rig.model.pitchDegrees) < 15.0 && controlledRecovery < 0) controlledRecovery = tick
            if (controlledRecovery >= 0) assertFalse(rig.model.stallActive,
                "stall relapse bank=$bank tick=$tick")
            assertFalse(rig.grounded, "recovery hit ground bank=$bank tick=$tick")
        }
        assertTrue(sawStall, "fixture must actually enter a stall")
        assertTrue(controlledRecovery in 0..recoveryLimit,
            "bank=$bank recovery=$controlledRecovery limit=$recoveryLimit")
        assertTrue(minimumHeight >= minimumAltitude,
            "bank=$bank minimum altitude=$minimumHeight")
        if (requireAltitudeCost) {
            assertTrue(minimumHeight < altitude - 1.0, "energy-limited stall recovery must spend altitude")
        }
    }

    @Test
    fun steepAndInvertedAttitudesRemainFiniteThroughInputReversals() {
        for (pitch in listOf(-89.5, 0.0, 89.5)) {
            for (roll in listOf(-179.5, -90.0, 90.0, 179.5)) {
                val rig = Rig(pitch = pitch, roll = roll, height = 500.0)
                repeat(180) { tick ->
                    val edge = when (tick % 60) { 0 -> 12.0; 30 -> -24.0; else -> 0.0 }
                    rig.step(throttle = 1.0, pitch = edge, roll = edge, rudder = 0.3)
                    for (value in listOf(rig.x, rig.y, rig.z, rig.speed(),
                        rig.model.yawDegrees, rig.model.pitchDegrees, rig.model.rollDegrees)) {
                        assertTrue(value.isFinite(), "pose=$pitch/$roll tick=$tick value=$value")
                    }
                    assertTrue(rig.speed() <= handling.hardSpeedLimitMps + 1e-9, "bounded game speed at pose=$pitch/$roll")
                    assertTrue(abs(rig.model.pitchDegrees) <= 90.0)
                    assertTrue(abs(rig.model.rollDegrees) <= 180.0)
                }
            }
        }
    }

    @Test
    fun mouseDeltaIsBoundedConsumedOnceAndCannotSurvivePilotAba() {
        val input = FixedWingPilotMouseInput()
        val pilot = UUID(1L, 1L)
        val other = UUID(2L, 2L)
        assertTrue(input.offer(pilot, 2.0, 3.0))
        assertTrue(input.offer(pilot, 4.0, 5.0))
        input.consume(pilot)
        assertEquals(6.0, input.sampledX, 0.0)
        assertEquals(8.0, input.sampledY, 0.0)
        input.consume(pilot)
        assertEquals(0.0, input.sampledX, 0.0)
        assertEquals(0.0, input.sampledY, 0.0)
        assertTrue(input.offer(pilot, 10.0, 10.0))
        input.clear()
        assertTrue(input.offer(other, 20.0, 20.0))
        input.clear()
        input.consume(pilot)
        assertEquals(0.0, input.sampledX, 0.0)
        assertFalse(input.offer(pilot, Double.POSITIVE_INFINITY, 1.0))
        repeat(20) { assertTrue(input.offer(pilot, 512.0, -512.0)) }
        input.consume(pilot)
        assertEquals(512.0, input.sampledX, 0.0)
        assertEquals(-512.0, input.sampledY, 0.0)
    }

    @Test
    fun publishedControlSurfacesRejectNonfiniteOrOutOfRangeValues() {
        val snapshot = FixedWingControlSurfaceSnapshot(1L, 1F, -1F, 0F, 1F, 0.5F, false)
        assertEquals(1F, snapshot.elevator)
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(elevator = Float.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot.copy(rudder = 1.01F)
        }
    }
}
