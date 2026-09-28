
package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.math.*

class FixedWingTakeoffResponseTest {
    private val handling = FixedWingHandlingProfile.GAME_JET
    private object Neutral : FixedWingSurfaceInput {
        override val elevatorCommand = 0.0
        override val aileronCommand = 0.0
        override val rudderCommand = 0.0
        override val groundRollAuthority = 0.0
        override val groundYaw = true
    }
    private fun field(value: Any, name: String) =
        value.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun model(): FixedWingFlightModel = FixedWingFlightModel(handling).also {
        it.reset(0.0, 0.0, 0.0)
        field(it, "throttle").setDouble(it, 1.0)
    }
    private fun step(m: FixedWingFlightModel, t: Int, grounded: Boolean = true,
                     vx: Double = 0.0, vz: Double = 5.0, enabled: Boolean = true,
                     engine: Double = 1.0, gear: Double = 1.0, brake: Boolean = false) {
        assertTrue(m.step(t.toLong(), vx, 0.0, vz, grounded, enabled,
            airbrakeRequested = brake, engineAvailability = engine,
            gearDeployment = gear, surfaces = Neutral))
    }
    private fun assistTicks(m: FixedWingFlightModel) = field(m, "poweredGroundTicks").getInt(m)
    private fun duplicate(m: FixedWingFlightModel, profile: FixedWingHandlingProfile = handling): FixedWingFlightModel =
        FixedWingFlightModel(profile).also { copy ->
        for (f in m.javaClass.declaredFields) if (f.type.isPrimitive && !Modifier.isStatic(f.modifiers)) {
            f.isAccessible = true
            f.set(copy, f.get(m))
        }
        field(copy, "poweredGroundTicks").setInt(copy, 0)
    }
    private fun assertStateEqual(a: FixedWingFlightModel, b: FixedWingFlightModel) {
        for (f in a.javaClass.declaredFields) if (f.type.isPrimitive && !Modifier.isStatic(f.modifiers)) {
            f.isAccessible = true
            assertEquals(f.get(a), f.get(b), f.name)
        }
    }

    @Test fun responseTuningKeepsPitchAndAppliesRequestedAccelerationAndSpeedEnvelope() {
        assertEquals(handling.pitchRateDegreesPerSecond * 1.10 * 1.12, handling.gamePitchRateDegreesPerSecond, 1e-12)
        // jets fly on their reference thrust (2026-09-28); propellers keep a x1.6 gain
        assertEquals(handling.dryAccelerationMps2, handling.gameDryAccelerationMps2, 1e-12)
        assertEquals(650.0 / 3.6, handling.softSpeedLimitMps, 1e-12)
        assertEquals(750.0 / 3.6, handling.hardSpeedLimitMps, 1e-12)
    }

    @Test fun stablePoweredContactAcquiresButContactFlickerNeverDoes() {
        val m = model()
        step(m, 0)
        assertEquals(1, assistTicks(m))
        val firstVelocity = m.velocityZ
        step(m, 1)
        assertEquals(2, assistTicks(m))
        assertTrue(m.velocityZ > firstVelocity)
        for (t in 2..19) {
            step(m, t, grounded = t % 2 != 0)
            assertTrue(assistTicks(m) <= 1)
        }
        m.resetControls(preserveThrottle = true)
        assertEquals(0, assistTicks(m))
        step(m, 20)
        assertEquals(1, assistTicks(m))
    }

    @Test fun exclusionsClearContactAssistanceWithoutChangingTheOrdinaryStep() {
        for (reason in listOf("air", "brake", "reverse", "lateral", "engine", "gear", "controller", "idle")) {
            val m = model()
            step(m, 0); step(m, 1)
            val control = duplicate(m)
            if (reason == "idle") {
                field(m, "throttle").setDouble(m, 0.0)
                field(control, "throttle").setDouble(control, 0.0)
            }
            for (value in listOf(m, control)) step(value, 2,
                grounded = reason != "air",
                vx = if (reason == "lateral") 8.0 else 0.0,
                vz = if (reason == "reverse") -5.0 else 5.0,
                enabled = reason != "controller",
                engine = if (reason == "engine") 0.0 else 1.0,
                gear = if (reason == "gear") 0.0 else 1.0,
                brake = reason == "brake")
            assertEquals(0, assistTicks(m), reason)
            assertStateEqual(m, control)
        }
    }

    @Test fun runwayAssistanceAcceleratesLowSpeedRollAndPreservesWorkBalance() {
        fun fifty(grounded: Boolean): Int {
            val m = model()
            field(m, "throttle").setDouble(m, 0.0)
            var v = 0.0
            var distance = 0.0
            for (t in 1..1000) {
                assertTrue(m.step(t.toLong(), 0.0, 0.0, v, grounded, true,
                    throttleAxis = 1.0, gearDeployment = 1.0, surfaces = Neutral))
                v = m.velocityZ
                distance += v * 0.05
                val work = m.stepThrustWorkPerKg + m.stepGravityWorkPerKg +
                    m.stepDragWorkPerKg + m.stepSideWorkPerKg + m.stepLiftWorkPerKg +
                    m.stepGroundResistanceWorkPerKg
                assertEquals(m.postStepKineticEnergyPerKg - m.preStepKineticEnergyPerKg, work, 1.0e-8)
                if (v * 3.6 >= 50.0) {
                    println("RUNWAY_RESPONSE grounded=$grounded ticks=$t distance_metres=$distance")
                    return t
                }
            }
            fail<Unit>("50 HUD km/h not reached")
            return 1001
        }
        val groundTicks = fifty(true)
        // The same idle-to-full-throttle fixture previously needed 68 ticks. Spool timing stays
        // unchanged; doubled runway propulsion must shorten the roll substantially.
        assertTrue(groundTicks in 30..50, "ground launch took $groundTicks ticks")
        assertEquals(51, fifty(false), "airborne low-speed acceleration must remain unchanged")
    }

    @Test fun fixedWheelsQualifyWithoutAddingRetractableGearDrag() {
        val fixed = model()
        val noWheels = model()
        for (t in 0..1) {
            for ((m, wheels) in listOf(fixed to true, noWheels to false)) {
                assertTrue(m.step(t.toLong(), 0.0, 0.0, 5.0, true, true,
                    surfaces = Neutral, gearDeployment = 0.0, wheelsDeployed = wheels))
            }
            if (t == 0) assertEquals(noWheels.velocityZ, fixed.velocityZ)
        }
        assertTrue(fixed.runwayContactAdmitted)
        assertEquals(2.0, fixed.runwayLaunchMultiplier, 1e-10)
        assertFalse(noWheels.runwayContactAdmitted)
        assertTrue(fixed.velocityZ > noWheels.velocityZ)
    }

    @Test fun fixedWheelCapabilityDoesNotChangeAirborneOrAboveFiftyMotion() {
        for ((grounded, speed) in listOf(false to 5.0, false to 40.0, true to 20.0)) {
            val withWheels = model()
            val withoutWheels = model()
            for (t in 0..3) {
                for ((m, wheels) in listOf(withWheels to true, withoutWheels to false)) {
                    assertTrue(m.step(t.toLong(), 0.0, 0.0, speed, grounded, true,
                        surfaces = Neutral, gearDeployment = 0.0, wheelsDeployed = wheels))
                }
                assertEquals(withoutWheels.velocityX, withWheels.velocityX)
                assertEquals(withoutWheels.velocityY, withWheels.velocityY)
                assertEquals(withoutWheels.velocityZ, withWheels.velocityZ)
            }
        }
    }

    @Test fun mouseTakeoffFromRestRemovesGroundAssistAtLiftoff() {
        for ((name, profile) in listOf("game_jet" to handling,
            "mig19" to MiG19FixedWingProfile.HANDLING,
            "scaled_jet" to FixedWingReferenceHandling.scale(handling, 0.25))) {
            val model = FixedWingFlightModel(profile)
            val controller = FixedWingMouseAimController(profile)
            model.reset(0.0, 0.0, 0.0)
            val target = ray(0.0, 15.0, 0.0, false, false)
            var vx = 0.0; var vz = 0.0; var distance = 0.0
            var takeoffTick = 0
            for (tick in 1..1000) {
                assertTrue(controller.update(model, target, true, true, vx, 0.0, vz, 1.0))
                assertTrue(model.step(tick.toLong(), vx, 0.0, vz, true, true,
                    throttleAxis = 1.0, gearDeployment = 1.0, surfaces = controller))
                vx = model.velocityX; vz = model.velocityZ
                distance += hypot(vx, vz) * 0.05
                if (model.velocityY > 1e-5) { takeoffTick = tick; break }
            }
            assertTrue(takeoffTick > 0, "$name mouse takeoff failed")
            println("MOUSE_TAKEOFF profile=$name ticks=$takeoffTick distance_metres=$distance speed_mps=${hypot(vx, vz)}")
            val control = duplicate(model, profile)
            val vy = model.velocityY
            for (value in listOf(model, control)) assertTrue(value.step((takeoffTick + 1).toLong(),
                vx, vy, vz, false, true, throttleAxis = 1.0,
                gearDeployment = 1.0, surfaces = controller))
            assertStateEqual(model, control)
            assertEquals(0, assistTicks(model))
        }
    }

    private fun ray(heading: Double, elevation: Double, side: Double, fp: Boolean, lower: Boolean) =
        FixedWingPilotIntent(-sin(Math.toRadians(heading)) * cos(Math.toRadians(elevation)),
            sin(Math.toRadians(elevation)), cos(Math.toRadians(heading)) * cos(Math.toRadians(elevation)),
            0, (side * 0.8).toFloat(), fp, lower)

    @Test fun renewedLowerTargetInterruptsRolloutButHeldAndLateralTargetsDoNot() {
        for (fp in listOf(false, true)) for (side in listOf(-1.0, 1.0))
        for (dive in listOf(-20.0, -50.0)) for (kind in listOf("held", "renew", "lateral")) {
            val m = model()
            m.reset(0.0, -dive, side * 100.0)
            val c = FixedWingMouseAimController(handling)
            val anchor = ray(0.0, dive, side, fp, true)
            for ((name, value) in mapOf("rollout" to true, "captured" to true,
                "captureWeight" to 1.0, "ordinaryCapture" to true, "ordinaryCaptureWeight" to 1.0,
                "rolloutAnchor" to anchor, "previousIntent" to anchor, "previousRateIntent" to anchor,
                "previousInversionRequested" to true)) field(c, name).set(c, value)
            val target = when (kind) {
                "renew" -> ray(side * 0.5, dive - 2.5, side, fp, true)
                "lateral" -> ray(side * 2.5, dive, side, fp, false)
                else -> anchor
            }
            assertTrue(c.update(m, target, true, false, 0.0,
                sin(Math.toRadians(dive)) * handling.trimSpeedMps * 1.5,
                cos(Math.toRadians(dive)) * handling.trimSpeedMps * 1.5, 1.0))
            assertEquals(kind == "renew", field(c, "positiveLiftCapture").getBoolean(c), kind)
            if (kind == "renew") assertFalse(field(c, "rollout").getBoolean(c))
            if (kind == "held") assertTrue(field(c, "rollout").getBoolean(c))
            assertTrue(c.update(m, null, false, false, 0.0, 0.0, handling.trimSpeedMps, 1.0))
            assertEquals(0.0, c.elevatorCommand)
            assertEquals(0.0, c.aileronCommand)
        }
    }

    @Test fun movingLowerTargetCannotStartUprightRecoveryBeforeTargetMotionStops() {
        val r = Math.PI / 180.0
        for (fp in listOf(false, true)) for (side in listOf(-1.0, 1.0)) {
            val m = model(); m.reset(0.0, 0.0, 0.0)
            val c = FixedWingMouseAimController(handling)
            var vx = 0.0; var vy = 0.0; var vz = handling.trimSpeedMps * 1.5
            var prematureRollout = 0; var tailError = 0.0; var tailBank = 0.0
            for (tick in 0..799) {
                val progress = tick.coerceAtMost(250)
                val target = ray(side * (10.0 + progress * 0.08),
                    -15.0 - progress * 0.03, side, fp, true)
                assertTrue(c.update(m, target, true, false, vx, vy, vz, 1.0))
                if (tick in 3..249 && field(c, "rollout").getBoolean(c)) prematureRollout++
                assertTrue(m.step(tick.toLong(), vx, vy, vz, false, true,
                    throttleAxis = 1.0, surfaces = c))
                vx = m.velocityX; vy = m.velocityY; vz = m.velocityZ
                if (tick >= 700) {
                    val fx = 2 * (m.quaternionX * m.quaternionZ + m.quaternionY * m.quaternionW)
                    val fy = 2 * (m.quaternionY * m.quaternionZ - m.quaternionX * m.quaternionW)
                    val fz = 1 - 2 * (m.quaternionX * m.quaternionX + m.quaternionY * m.quaternionY)
                    tailError = max(tailError, acos((fx * target.directionX +
                        fy * target.directionY + fz * target.directionZ).coerceIn(-1.0, 1.0)) / r)
                    tailBank = max(tailBank, abs(m.rollDegrees))
                }
            }
            assertEquals(0, prematureRollout)
            assertTrue(tailError < 0.1, "held lower target error=$tailError bank=$tailBank fp=$fp side=$side")
            assertTrue(tailBank < 0.1, "held lower target bank=$tailBank fp=$fp side=$side")
        }
    }

}

