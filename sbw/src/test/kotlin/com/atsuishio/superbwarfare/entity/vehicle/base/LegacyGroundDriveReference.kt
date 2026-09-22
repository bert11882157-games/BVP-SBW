package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.util.Mth
import org.joml.Math

/** Frozen pre-refactor arithmetic for exact differential tests. */
internal object LegacyGroundDriveReference {

    fun advanceTrackControls(input: GroundDriveControlInput): GroundDriveControlResult {
        var forwardInputDown = input.controls.forward
        var backInputDown = input.controls.backward
        var leftInputDown = input.controls.left
        var rightInputDown = input.controls.right
        val sprintInputDown = input.controls.sprint
        val upInputDown = input.controls.brake
        val isInFluidType = input.inFluid
        var power = input.power
        var deltaRot = input.rotation
        var holdTick = input.holdTicks
        val targetSpeed: Double
        val xRot = input.pitchDegrees
        val powerAdd = input.increment
        val powerReduce = input.decrement
        val steeringSpeed = input.steeringSpeed
        val maxForwardSpeedRate = input.maxForwardSpeedRate
        val maxBackwardSpeedRate = input.maxBackwardSpeedRate
        fun drift() = upInputDown && (rightInputDown || leftInputDown)

        if (!input.operationalPower) {
            forwardInputDown = false
            backInputDown = false
            leftInputDown = false
            rightInputDown = false
            power *= 0.95f
        }

        if (!input.occupied) {
            leftInputDown = false
            rightInputDown = false
            forwardInputDown = false
            backInputDown = false
            power = 0f
        }

        val maxPower = if (sprintInputDown) 1.25f else (if (power > 1) power - 0.002f else 1f)

        if (forwardInputDown && !backInputDown) {
            power = Math.min(power + (if (power < 0) powerAdd * 2f else powerAdd) * (maxPower - (Mth.abs(power) / 1.02f)), maxPower)
        }

        if (backInputDown) {
            power = Math.max(power - (if (power > 0) powerReduce * 4f else powerReduce) * (maxPower - (Mth.abs(power) / 1.02f)), -1f)
            if (rightInputDown) {
                holdTick++
                deltaRot += steeringSpeed * 0.12f * Math.min(holdTick, 10)
            } else if (leftInputDown) {
                holdTick++
                deltaRot -= steeringSpeed * 0.12f * Math.min(holdTick, 10)
            } else {
                holdTick = 0
            }
        } else {
            if (rightInputDown) {
                holdTick++
                deltaRot -= steeringSpeed * 0.12f * Math.min(holdTick, 10)
            } else if (leftInputDown) {
                holdTick++
                deltaRot += steeringSpeed * 0.12f * Math.min(holdTick, 10)
            } else {
                holdTick = 0
            }
        }

        targetSpeed = if (power > 0) {
            (maxForwardSpeedRate * (1 + xRot / 40)).toDouble()
        } else {
            (maxBackwardSpeedRate * (1 - xRot / 40)).toDouble()
        }

        if (!forwardInputDown && !backInputDown) {
            power *= 0.96f
        }

        if (upInputDown) {
            power *= if (isInFluidType) 0.97f else (if (drift()) 0.96f else 0.6f)
        }

        if (rightInputDown || leftInputDown) {
            power *= 0.995f
        }

        return GroundDriveControlResult(GroundDriveControls(forwardInputDown, backInputDown,
            leftInputDown, rightInputDown, sprintInputDown, upInputDown), power, deltaRot, holdTick, targetSpeed)
    }

    fun advanceWheelControls(input: GroundDriveControlInput): GroundDriveControlResult {
        var forwardInputDown = input.controls.forward
        var backInputDown = input.controls.backward
        var leftInputDown = input.controls.left
        var rightInputDown = input.controls.right
        val sprintInputDown = input.controls.sprint
        val upInputDown = input.controls.brake
        val isInFluidType = input.inFluid
        var power = input.power
        var deltaRot = input.rotation
        var holdTick = input.holdTicks
        val targetSpeed: Double
        val xRot = input.pitchDegrees
        val powerAdd = input.increment
        val powerReduce = input.decrement
        val steeringSpeed = input.steeringSpeed
        val maxForwardSpeedRate = input.maxForwardSpeedRate
        val maxBackwardSpeedRate = input.maxBackwardSpeedRate
        fun drift() = upInputDown && (rightInputDown || leftInputDown)

        if (!input.operationalPower) {
            forwardInputDown = false
            backInputDown = false
            leftInputDown = false
            rightInputDown = false
            power *= 0.95f
            deltaRot *= 0.5f
        }

        if (!input.occupied) {
            leftInputDown = false
            rightInputDown = false
            forwardInputDown = false
            backInputDown = false
            power = 0f
        }

        val maxPower = if (sprintInputDown) 1.3f else (if (power > 1) power - 0.002f else 1f)

        if (forwardInputDown && !backInputDown) {
            power = Math.min(power + (if (power < 0) powerAdd * 2f else powerAdd) * (maxPower - (Mth.abs(power) / 1.02f)), maxPower)
        }

        if (backInputDown) {
            power = Math.max(
                power - (if (power > 0) powerReduce * 4f else powerReduce) * (maxPower - (Mth.abs(power) / 1.02f)), -1f
            )
        }

        targetSpeed = if (power > 0) {
            (maxForwardSpeedRate * (1 + xRot / 40)).toDouble()
        } else {
            (maxBackwardSpeedRate * (1 - xRot / 40)).toDouble()
        }

        if (!forwardInputDown && !backInputDown) {
            power *= 0.97f
        }

        if (upInputDown) {
            power *= if (isInFluidType) 0.97f else (if (drift()) 0.93f else 0.6f)
        }

        if (rightInputDown || leftInputDown) {
            power *= 0.995f
        }

        return GroundDriveControlResult(GroundDriveControls(forwardInputDown, backInputDown,
            leftInputDown, rightInputDown, sprintInputDown, upInputDown), power, deltaRot, holdTick, targetSpeed)
    }

    fun trackRotation(rotation: Float, horizontalSpeed: Double): Float =
        rotation * Math.max(0.76f - 0.1f * horizontalSpeed, 0.3).toFloat()

    fun prepareWheelSteering(input: GroundDriveSteeringInput): GroundDriveSteeringResult {
        var power = input.power
        var deltaRot = input.rotation
        var holdTick = input.holdTicks
        var steeringSpeed = input.steeringSpeed
        val leftWheelDamaged = input.leftDamaged
        val rightWheelDamaged = input.rightDamaged
        val mainEngineDamaged = input.engineDamaged
        val rightInputDown = input.controls.right
        val leftInputDown = input.controls.left
        fun drift() = input.controls.drifting
        val i: Int
        if (leftWheelDamaged && rightWheelDamaged) {
            power *= 0.93f
            i = 0
        } else if (leftWheelDamaged) {
            power *= 0.975f
            i = 3
        } else if (rightWheelDamaged) {
            power *= 0.975f
            i = -3
        } else {
            i = 0
        }

        if (mainEngineDamaged) {
            power *= 0.875f
        }

        if (drift()) {
            steeringSpeed *= 1.5f
        }

        if (rightInputDown) {
            holdTick++
            deltaRot += steeringSpeed * 0.12f * Math.min(holdTick, 10)
        } else if (leftInputDown) {
            holdTick++
            deltaRot -= steeringSpeed * 0.12f * Math.min(holdTick, 10)
        } else {
            holdTick = 0
        }

        deltaRot *= Math.max(0.78f - 0.25f * input.horizontalSpeed, 0.1).toFloat()


        return GroundDriveSteeringResult(power, deltaRot, holdTick, i)
    }

    fun finishTrack(input: GroundDriveFinishInput): GroundDriveFinishResult {
        var leftWheelRot = input.leftWheelRot
        var rightWheelRot = input.rightWheelRot
        var leftTrack = input.leftTrack
        var rightTrack = input.rightTrack
        var rudderRot = input.rudderRot
        var power = input.power
        var yRot = input.yawDegrees
        val deltaRot = input.rotation
        val wheelRotSpeed = input.wheelRotSpeed
        val wheelDifferential = input.wheelDifferential
        val trackSpeed = input.trackSpeed
        val trackDifferential = input.trackDifferential
        val leftDrift = input.leftDrift
        val rightDrift = input.rightDrift
        val s0 = input.longitudinalMotion
        val isInFluidType = input.inFluid
        val leftWheelDamaged = input.leftDamaged
        val rightWheelDamaged = input.rightDamaged
        val mainEngineDamaged = input.engineDamaged
        fun onGround() = input.onGround
        leftWheelRot = ((leftWheelRot - wheelRotSpeed * s0 * leftDrift) + Mth.clamp(
            wheelDifferential * deltaRot * leftDrift, -5.0, 5.0
        )).toFloat()
        rightWheelRot = ((rightWheelRot - wheelRotSpeed * s0 * rightDrift) - Mth.clamp(
            wheelDifferential * deltaRot * rightDrift, -5.0, 5.0
        )).toFloat()

        leftTrack = ((leftTrack - trackSpeed * java.lang.Math.PI * s0 * leftDrift) + Mth.clamp(
            trackDifferential * java.lang.Math.PI * deltaRot * leftDrift, -5.0, 5.0
        )).toFloat()
        rightTrack = ((rightTrack - trackSpeed * java.lang.Math.PI * s0 * rightDrift) - Mth.clamp(
            trackDifferential * java.lang.Math.PI * deltaRot * rightDrift, -5.0, 5.0
        )).toFloat()

        val i: Int
        if (leftWheelDamaged && rightWheelDamaged) {
            power *= 0.93f
            i = 0
        } else if (leftWheelDamaged) {
            power *= 0.975f
            i = 3
        } else if (rightWheelDamaged) {
            power *= 0.975f
            i = -3
        } else {
            i = 0
        }

        if (mainEngineDamaged) {
            power *= 0.96f
        }

        yRot = (yRot - (if (isInFluidType && !onGround()) 2.5 else 8.0) * deltaRot - i * s0).toFloat()

        return GroundDriveFinishResult(power, yRot, leftWheelRot, rightWheelRot, leftTrack, rightTrack, rudderRot)
    }

    fun finishWheel(input: GroundDriveFinishInput): GroundDriveFinishResult {
        var leftWheelRot = input.leftWheelRot
        var rightWheelRot = input.rightWheelRot
        var leftTrack = input.leftTrack
        var rightTrack = input.rightTrack
        var rudderRot = input.rudderRot
        var power = input.power
        var yRot = input.yawDegrees
        val deltaRot = input.rotation
        val wheelRotSpeed = input.wheelRotSpeed
        val wheelDifferential = input.wheelDifferential
        val trackSpeed = input.trackSpeed
        val trackDifferential = input.trackDifferential
        val leftDrift = input.leftDrift
        val rightDrift = input.rightDrift
        val s0 = input.longitudinalMotion
        val isInFluidType = input.inFluid
        val leftWheelDamaged = input.leftDamaged
        val rightWheelDamaged = input.rightDamaged
        val mainEngineDamaged = input.engineDamaged
        fun onGround() = input.onGround
        val i = input.damageBias
        leftWheelRot = ((leftWheelRot - wheelRotSpeed * s0) - Mth.clamp(
            wheelDifferential * deltaRot, -5.0, 5.0
        ) * input.motionLength).toFloat()
        rightWheelRot = ((rightWheelRot - wheelRotSpeed * s0) + Mth.clamp(
            wheelDifferential * deltaRot, -5.0, 5.0
        ) * input.motionLength).toFloat()

        rudderRot = Mth.clamp(
            rudderRot - deltaRot,
            -0.8f,
            0.8f
        ) * 0.75f

        var steeringYaw = Math.max(
            (if (isInFluidType && !onGround()) 6 else 12) * input.horizontalSpeed, 0.0
        ) * rudderRot * (if (power > 0) 1 else -1)
        if (onGround() && !isInFluidType) {
            steeringYaw = limitGroundWheelYaw(
                steeringYaw,
                input.horizontalSpeed,
                input.groundDamping,
                input.gravity
            )
        }

        yRot = (yRot - steeringYaw - i * s0).toFloat()

        return GroundDriveFinishResult(power, yRot, leftWheelRot, rightWheelRot, leftTrack, rightTrack, rudderRot)
    }

    /**
     * Limits only the ground-wheel steering component to the lateral acceleration
     * already implied by the wheel branch's retained-speed damping.  The damping
     * value is a per-tick velocity retention, so (1 - retention) * gravity is a
     * bounded traction proxy derived from the existing damping and gravity values.
     */
    fun limitGroundWheelYaw(
        rawYawDegrees: Double,
        speed: Double,
        groundDamping: Double,
        gravity: Double
    ): Double {
        if (!rawYawDegrees.isFinite() || !speed.isFinite() || speed <= 1.0E-4 ||
            !groundDamping.isFinite() || !gravity.isFinite() || gravity <= 0.0
        ) {
            return rawYawDegrees
        }

        val lateralGrip = (1.0 - groundDamping).coerceIn(0.0, 1.0)
        val yawLimit = java.lang.Math.toDegrees(java.lang.Math.atan2(lateralGrip * gravity, speed))
        return if (yawLimit.isFinite()) rawYawDegrees.coerceIn(-yawLimit, yawLimit) else rawYawDegrees
    }

}
