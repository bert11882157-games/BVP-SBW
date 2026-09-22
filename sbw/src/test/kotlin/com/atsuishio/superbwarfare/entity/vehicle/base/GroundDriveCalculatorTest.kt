package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.objectweb.asm.*

class GroundDriveCalculatorTest {
    private fun controls(bits: Int) = GroundDriveControls(bits and 1 != 0, bits and 2 != 0,
        bits and 4 != 0, bits and 8 != 0, bits and 16 != 0, bits and 32 != 0)

    @Test fun `all controls admission environment and damage combinations preserve arithmetic bits`() {
        val powers = listOf(-1.2f, -0.2f, 0f, 0.8f, 1.4f)
        val holds = listOf(0, 9, 10, 11, 40)
        for (bits in 0 until 64) for (flags in 0 until 128) for (initial in powers.indices) {
            val input = GroundDriveControlInput(controls(bits), powers[initial], (initial - 2) * 0.13f,
                holds[initial], (initial - 2) * 7.5f, flags and 1 != 0,
                flags and 2 != 0, flags and 4 != 0, 0.001f, 0.002f, 0.13f, 0.2f, -0.1f)
            val track = GroundDriveCalculator.advanceTrackControls(input)
            val wheel = GroundDriveCalculator.advanceWheelControls(input)
            assertControlBits(LegacyGroundDriveReference.advanceTrackControls(input), track)
            assertControlBits(LegacyGroundDriveReference.advanceWheelControls(input), wheel)
            val steering = GroundDriveSteeringInput(wheel.controls, wheel.power, wheel.rotation, wheel.holdTicks,
                0.13f, initial * 0.31, flags and 8 != 0, flags and 16 != 0, flags and 32 != 0)
            val prepared = GroundDriveCalculator.prepareWheelSteering(steering)
            assertEquals(LegacyGroundDriveReference.prepareWheelSteering(steering), prepared)
            assertEquals(LegacyGroundDriveReference.trackRotation(track.rotation, initial * 0.31).toRawBits(),
                GroundDriveCalculator.trackRotation(track.rotation, initial * 0.31).toRawBits())
            val finish = GroundDriveFinishInput(
                power = prepared.power, rotation = prepared.rotation, yawDegrees = -37f,
                leftWheelRot = 10f, rightWheelRot = -12f, leftTrack = 3.25f, rightTrack = -4.5f,
                rudderRot = 0.17f, wheelRotSpeed = 0.45, wheelDifferential = 0.2,
                trackSpeed = 0.18, trackDifferential = 0.21, leftDrift = if (bits and 4 == 0) 1f else 0f,
                rightDrift = if (bits and 8 == 0) 1f else 0f, longitudinalMotion = (initial - 2) * 0.27,
                motionLength = initial * 0.4, horizontalSpeed = initial * 0.31,
                inFluid = flags and 1 != 0, onGround = flags and 64 != 0,
                leftDamaged = flags and 8 != 0, rightDamaged = flags and 16 != 0,
                engineDamaged = flags and 32 != 0, damageBias = prepared.damageBias,
                groundDamping = 0.73, gravity = 0.08)
            assertFinishBits(LegacyGroundDriveReference.finishTrack(finish), GroundDriveCalculator.finishTrack(finish))
            val expectedWheel = LegacyGroundDriveReference.finishWheel(finish)
            val actualWheel = GroundDriveCalculator.finishWheel(finish)
            val expectedYaw = if (finish.onGround && !finish.inFluid) {
                val yaw = LegacyGroundDriveReference.limitGroundWheelYaw(
                    12.0 * finish.longitudinalMotion * expectedWheel.rudderRot,
                    finish.horizontalSpeed, finish.groundDamping, finish.gravity)
                (finish.yawDegrees - yaw - finish.damageBias * finish.longitudinalMotion).toFloat()
            } else expectedWheel.yawDegrees
            assertFinishBits(expectedWheel.copy(yawDegrees = expectedYaw), actualWheel)
        }
    }

    private fun assertControlBits(expected: GroundDriveControlResult, actual: GroundDriveControlResult) {
        assertEquals(expected, actual)
        assertEquals(expected.power.toRawBits(), actual.power.toRawBits())
        assertEquals(expected.rotation.toRawBits(), actual.rotation.toRawBits())
        assertEquals(expected.targetSpeed.toRawBits(), actual.targetSpeed.toRawBits())
    }

    private fun assertFinishBits(expected: GroundDriveFinishResult, actual: GroundDriveFinishResult) {
        assertEquals(expected, actual)
        assertEquals(listOf(expected.power, expected.yawDegrees, expected.leftWheelRot, expected.rightWheelRot,
            expected.leftTrack, expected.rightTrack, expected.rudderRot).map(Float::toRawBits),
            listOf(actual.power, actual.yawDegrees, actual.leftWheelRot, actual.rightWheelRot,
                actual.leftTrack, actual.rightTrack, actual.rudderRot).map(Float::toRawBits))
    }

    @Test fun `multi-tick control traces preserve reversal braking and hold-counter progression`() {
        for (seed in 0..31) {
            var actual = GroundDriveControlInput(controls(seed), 0f, 0f, 0, 0f,
                false, true, true, 0.001f, 0.002f, 0.13f, 0.2f, -0.1f)
            var expected = actual
            repeat(200) { tick ->
                val c = controls((seed * 17 + tick / 7) % 64)
                actual = actual.copy(controls = c, operationalPower = tick % 19 != 0, occupied = tick % 31 != 0)
                expected = expected.copy(controls = c, operationalPower = tick % 19 != 0, occupied = tick % 31 != 0)
                val a = if (seed % 2 == 0) GroundDriveCalculator.advanceTrackControls(actual)
                    else GroundDriveCalculator.advanceWheelControls(actual)
                val e = if (seed % 2 == 0) LegacyGroundDriveReference.advanceTrackControls(expected)
                    else LegacyGroundDriveReference.advanceWheelControls(expected)
                assertControlBits(e, a)
                actual = actual.copy(power = a.power, rotation = a.rotation, holdTicks = a.holdTicks)
                expected = expected.copy(power = e.power, rotation = e.rotation, holdTicks = e.holdTicks)
            }
        }
    }

    @Test fun `energy and overridden view observations see committed phase state`() {
        val order = mutableListOf("resistance", "particles")
        var power = 0f
        var rotation = 0f
        var yaw = 0f
        fun overriddenView(): Double { order += "view:$rotation:$yaw"; return rotation + yaw.toDouble() }
        GroundDrivePhases.execute(
            advanceControls = { order += "controls"; 0.5f },
            commitControls = { power = it },
            consumePower = { assertEquals(0.5f, power); order += "energy"; power = 0.25f },
            prepareSteering = { assertEquals(0.25f, power); order += "steering"; 2f },
            commitSteering = { rotation = it },
            sampleLongitudinal = { overriddenView() },
            finishDrive = { _, longitudinal -> assertEquals(2.0, longitudinal); order += "finish"; 4f },
            commitDrive = { yaw = it },
            applyThrust = { assertEquals(6.0, overriddenView()); order += "thrust" },
        )
        assertEquals(listOf("resistance", "particles", "controls", "energy", "steering", "view:2.0:0.0",
            "finish", "view:2.0:4.0", "thrust"), order)
    }

    @Test fun `wheel traction clamp retains valid-zero and nonfinite guards`() {
        for (yaw in listOf(-20.0, 0.0, 20.0, Double.NaN)) for (speed in listOf(0.0, 0.0001, 0.25, 2.0)) {
            assertEquals(LegacyGroundDriveReference.limitGroundWheelYaw(yaw, speed, 0.73, 0.08).toRawBits(),
                GroundDriveCalculator.limitGroundWheelYaw(yaw, speed, 0.73, 0.08).toRawBits())
        }
    }

    @Test fun `compiled adapter preserves calculation energy steering view and yaw barriers`() {
        javaClass.classLoader.getResourceAsStream(
            "com/atsuishio/superbwarfare/entity/vehicle/base/GroundDriveCalculator.class")!!.use {
            val constants = String(it.readBytes(), Charsets.ISO_8859_1)
            for (forbidden in listOf("VehicleEntity", "EngineInfo", "net/minecraft/world/level", "kotlin/jvm/functions/Function")) {
                assertFalse(constants.contains(forbidden), "Calculator dependency: $forbidden")
            }
        }
        val calls = mutableMapOf<String, MutableList<String>>()
        javaClass.classLoader.getResourceAsStream(
            "com/atsuishio/superbwarfare/entity/vehicle/base/VehicleGroundMotionService.class")!!.use {
            ClassReader(it).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?,
                    exceptions: Array<out String>?): MethodVisitor {
                    val list = calls.getOrPut(name) { mutableListOf() }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String,
                            isInterface: Boolean) { list += name.substringBefore('$') }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        for (kind in listOf("Track", "Wheel")) {
            val sequence = calls.getValue("step$kind")
            val controls = sequence.indexOf("advance${kind}Controls")
            val energy = sequence.indexOf("consumeOperationalPower")
            val steering = sequence.indexOf(if (kind == "Track") "trackRotation" else "prepareWheelSteering")
            val finish = sequence.indexOf("finish$kind")
            val yaw = sequence.lastIndexOf("setYRot")
            assertTrue(controls >= 0 && energy > controls && steering > energy && finish > steering)
            assertTrue(sequence.subList(steering, finish).contains("getViewVector"))
            assertTrue(yaw > finish && sequence.lastIndexOf("getViewVector") > yaw)
            assertTrue(sequence.lastIndexOf("addRandomParticle") < controls)
        }
    }
}
