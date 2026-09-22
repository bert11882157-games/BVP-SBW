package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.entity.vehicle.utils.GroundRestPolicy
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth

/**
 * Samples environment and commits ground-drive results through the compatibility facade.
 * GroundDriveCalculator owns controls/steering arithmetic over immutable phase inputs.
 * World/FX stay here; virtual view vectors are sampled after steering and after yaw commit.
 */
internal class VehicleGroundMotionService(private val vehicle: VehicleEntity) {
    fun stepTrack(engineInfo: EngineInfo.Track) = with(vehicle) {
        val buoyancy = engineInfo.buoyancy
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()
        val wheelRotSpeed = engineInfo.wheelRotSpeed
        val wheelDifferential = engineInfo.wheelDifferential
        val trackSpeed = engineInfo.trackRotSpeed
        val trackDifferential = engineInfo.trackDifferential
        val maxForwardSpeedRate = engineInfo.maxForwardSpeedRate
        val maxBackwardSpeedRate = engineInfo.maxBackwardSpeedRate
        var powerAdd = engineInfo.increment
        var powerReduce = engineInfo.decrement
        var steeringSpeed = engineInfo.steeringSpeed

        if (buoyancy != 0.0) {
            val fluidFloat = buoyancy * VehicleVecUtils.getSubmergedHeight(this)
            deltaMovement = deltaMovement.add(0.0, fluidFloat, 0.0)
        }

        val rightDrift = if (drift() && rightInputDown) 0f else 1f
        val leftDrift = if (drift() && leftInputDown) 0f else 1f

        if (onGround()) {

            var f0 = (if (drift()) 0.95f
            else 0.54f + 0.25f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat()))

            if (isInFluidType) {
                f0 -= 3f * VehicleVecUtils.getSubmergedHeight(this).toFloat() * deltaMovement.lengthSqr().toFloat()
            }

            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale((if (drift()) 0.001 else 0.05) * deltaMovement.dot(getViewVector(1f)))
            )

            deltaMovement = deltaMovement.multiply(f0.toDouble(), 0.99, f0.toDouble())

        } else if (isInFluidType) {

            powerAdd *= 0.1f
            powerReduce *= 0.1f

            val f1 = Mth.clamp(0.9f
                    + 0.09f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat())
                    - 4f * deltaMovement.lengthSqr().toFloat()
                - VehicleVecUtils.getSubmergedHeight(this).toFloat() * 0.02f
                , 0f, 0.99f)

            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.04 * deltaMovement.dot(getViewVector(1f)))
            )
            deltaMovement = deltaMovement.multiply(f1.toDouble(), 0.85, f1.toDouble())
        } else {
            deltaMovement = deltaMovement.multiply(0.99, 0.99, 0.99)
        }

        if (level().isClientSide) {
            if (isInFluidType && deltaMovement.horizontalDistanceSqr() > 0.3162) {
                addRandomParticle(ParticleTypes.CLOUD, position().add(
                    0.0,
                    VehicleVecUtils.getSubmergedHeight(this) - 0.2,
                    0.0),
                    1f, level(), 0f, (2 + 4 * deltaMovement.length()).toInt())

                addRandomParticle(ParticleTypes.BUBBLE_COLUMN_UP, position().add(
                    0.0,
                    VehicleVecUtils.getSubmergedHeight(this) - 0.2,
                    0.0), 1f, level(), 0f, (2 + 10 * deltaMovement.length()).toInt())

            }
        }

        GroundDrivePhases.execute(
            advanceControls = {
                val passenger = getFirstPassenger()
                val operationalPower = hasOperationalPower(energyCost)
                GroundDriveCalculator.advanceTrackControls(GroundDriveControlInput(
                    controls = GroundDriveControls(forwardInputDown, backInputDown, leftInputDown,
                        rightInputDown, sprintInputDown, upInputDown),
                    power = power, rotation = deltaRot, holdTicks = holdTick,
                    pitchDegrees = xRot, inFluid = isInFluidType, occupied = passenger != null,
                    operationalPower = operationalPower, increment = powerAdd, decrement = powerReduce,
                    steeringSpeed = steeringSpeed, maxForwardSpeedRate = maxForwardSpeedRate,
                    maxBackwardSpeedRate = maxBackwardSpeedRate))
            },
            commitControls = { next ->
                forwardInputDown = next.controls.forward
                backInputDown = next.controls.backward
                leftInputDown = next.controls.left
                rightInputDown = next.controls.right
                power = next.power
                deltaRot = next.rotation
                holdTick = next.holdTicks
                targetSpeed = next.targetSpeed
            },
            consumePower = { if (level() is ServerLevel) consumeOperationalPower(energyCost) },
            prepareSteering = {
                // Retain this legacy adjustment after control steering; it has no later consumer.
                if (drift()) steeringSpeed *= 3.4f
                GroundDriveCalculator.trackRotation(deltaRot, deltaMovement.horizontalDistance())
            },
            commitSteering = { deltaRot = GroundRestPolicy.settleSteering(it,
                GroundRestPolicy.isAtRest(onGround(), isInFluidType,
                    deltaMovement.horizontalDistanceSqr(), forwardInputDown || backInputDown ||
                        leftInputDown || rightInputDown)) },
            sampleLongitudinal = { deltaMovement.dot(getViewVector(1f)) },
            finishDrive = { steering, longitudinal ->
                GroundDriveCalculator.finishTrack(GroundDriveFinishInput(
                    power = power, rotation = deltaRot, yawDegrees = yRot,
                    leftWheelRot = leftWheelRot, rightWheelRot = rightWheelRot,
                    leftTrack = leftTrack, rightTrack = rightTrack, rudderRot = rudderRot,
                    wheelRotSpeed = wheelRotSpeed, wheelDifferential = wheelDifferential,
                    trackSpeed = trackSpeed, trackDifferential = trackDifferential,
                    leftDrift = leftDrift, rightDrift = rightDrift,
                    longitudinalMotion = longitudinal, motionLength = deltaMovement.length(),
                    horizontalSpeed = deltaMovement.horizontalDistance(),
                    inFluid = isInFluidType, onGround = onGround(),
                    leftDamaged = leftWheelDamaged, rightDamaged = rightWheelDamaged,
                    engineDamaged = mainEngineDamaged, damageBias = 0,
                    groundDamping = 0.0, gravity = 0.0))
            },
            commitDrive = { next ->
                leftWheelRot = next.leftWheelRot
                rightWheelRot = next.rightWheelRot
                leftTrack = next.leftTrack
                rightTrack = next.rightTrack
                power = next.power
                yRot = next.yawDegrees
            },
            applyThrust = {
                if (isInFluidType || onGround()) {
                    deltaMovement = deltaMovement.add(getViewVector(1f)
                        .scale((if (drift()) 0.03 else 0.15) * targetSpeed * power))
                }
            },
        )
    }

    fun stepWheel(engineInfo: EngineInfo.Wheel) = with(vehicle) {
        val buoyancy = engineInfo.buoyancy
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()
        val wheelRotSpeed = engineInfo.wheelRotSpeed
        val wheelDifferential = engineInfo.wheelDifferential
        val maxForwardSpeedRate = engineInfo.maxForwardSpeedRate
        val maxBackwardSpeedRate = engineInfo.maxBackwardSpeedRate
        var powerAdd = engineInfo.increment
        var powerReduce = engineInfo.decrement
        var steeringSpeed = engineInfo.steeringSpeed

        val level = level()

        if (buoyancy != 0.0) {
            val fluidFloat = buoyancy * VehicleVecUtils.getSubmergedHeight(this)
            deltaMovement = deltaMovement.add(0.0, fluidFloat, 0.0)
        }

        var groundDamping = 0.0
        if (onGround()) {
            var f0 = (if (drift()) 0.96f
            else 0.54f + 0.25f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat()))

            if (isInFluidType) {
                f0 -= 3f * VehicleVecUtils.getSubmergedHeight(this).toFloat() * deltaMovement.lengthSqr().toFloat()
            }

            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale((if (drift()) 0.001 else 0.05) * deltaMovement.dot(getViewVector(1f)))
            )

            deltaMovement = deltaMovement.multiply(f0.toDouble(), 0.99, f0.toDouble())
            groundDamping = f0.toDouble()
        } else if (isInFluidType) {
            powerAdd *= 0.1f
            powerReduce *= 0.1f

            val f1 = Mth.clamp(0.9f
                    + 0.09f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat())
                    - 4f * deltaMovement.lengthSqr().toFloat()
                    - VehicleVecUtils.getSubmergedHeight(this).toFloat() * 0.02f
                , 0f, 0.99f)

            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.04 * deltaMovement.dot(getViewVector(1f)))
            )
            deltaMovement = deltaMovement.multiply(f1.toDouble(), 0.85, f1.toDouble())
        } else {
            deltaMovement = deltaMovement.multiply(0.99, 0.99, 0.99)
        }

        if (level.isClientSide) {
            if (isInFluidType && deltaMovement.horizontalDistanceSqr() > 0.3162) {
                addRandomParticle(ParticleTypes.CLOUD, position().add(
                    0.0,
                    VehicleVecUtils.getSubmergedHeight(this) - 0.2,
                    0.0),
                    1f, level(), 0f, (2 + 4 * deltaMovement.length()).toInt())

                addRandomParticle(ParticleTypes.BUBBLE_COLUMN_UP, position().add(
                    0.0,
                    VehicleVecUtils.getSubmergedHeight(this) - 0.2,
                    0.0), 1f, level(), 0f, (2 + 10 * deltaMovement.length()).toInt())

            }

            if (upInputDown && onGround() && deltaMovement.horizontalDistanceSqr() > 0.01) {
                for (pos in computed().terrainCompat) {
                    val worldPosition = transformPosition(
                        getVehicleTransform(1f),
                        pos.x, pos.y, pos.z
                    )

                    val option = CustomCloudOption(0x000000, 200, 1.5f, 0f, false, false)

                    level().addParticle(option,
                        worldPosition.x,
                        worldPosition.y,
                        worldPosition.z,
                        0.0, 0.0, 0.0
                    )
                }
            }
        }

        GroundDrivePhases.execute(
            advanceControls = {
                val passenger = getFirstPassenger()
                val operationalPower = hasOperationalPower(energyCost)
                GroundDriveCalculator.advanceWheelControls(GroundDriveControlInput(
                    controls = GroundDriveControls(forwardInputDown, backInputDown, leftInputDown,
                        rightInputDown, sprintInputDown, upInputDown),
                    power = power, rotation = deltaRot, holdTicks = holdTick,
                    pitchDegrees = xRot, inFluid = isInFluidType, occupied = passenger != null,
                    operationalPower = operationalPower, increment = powerAdd, decrement = powerReduce,
                    steeringSpeed = steeringSpeed, maxForwardSpeedRate = maxForwardSpeedRate,
                    maxBackwardSpeedRate = maxBackwardSpeedRate))
            },
            commitControls = { next ->
                forwardInputDown = next.controls.forward
                backInputDown = next.controls.backward
                leftInputDown = next.controls.left
                rightInputDown = next.controls.right
                power = next.power
                deltaRot = next.rotation
                holdTick = next.holdTicks
                targetSpeed = next.targetSpeed
            },
            consumePower = { if (level is ServerLevel) consumeOperationalPower(energyCost) },
            prepareSteering = {
                GroundDriveCalculator.prepareWheelSteering(GroundDriveSteeringInput(
                    controls = GroundDriveControls(forwardInputDown, backInputDown, leftInputDown,
                        rightInputDown, sprintInputDown, upInputDown),
                    power = power, rotation = deltaRot, holdTicks = holdTick, steeringSpeed = steeringSpeed,
                    horizontalSpeed = deltaMovement.horizontalDistance(),
                    leftDamaged = leftWheelDamaged, rightDamaged = rightWheelDamaged,
                    engineDamaged = mainEngineDamaged))
            },
            commitSteering = { next ->
                power = next.power
                holdTick = next.holdTicks
                deltaRot = GroundRestPolicy.settleSteering(next.rotation,
                    GroundRestPolicy.isAtRest(onGround(), isInFluidType,
                        deltaMovement.horizontalDistanceSqr(), forwardInputDown || backInputDown ||
                            leftInputDown || rightInputDown))
            },
            sampleLongitudinal = { deltaMovement.dot(getViewVector(1f)) },
            finishDrive = { steering, longitudinal ->
                GroundDriveCalculator.finishWheel(GroundDriveFinishInput(
                    power = power, rotation = deltaRot, yawDegrees = yRot,
                    leftWheelRot = leftWheelRot, rightWheelRot = rightWheelRot,
                    leftTrack = leftTrack, rightTrack = rightTrack, rudderRot = rudderRot,
                    wheelRotSpeed = wheelRotSpeed, wheelDifferential = wheelDifferential,
                    trackSpeed = 0.0, trackDifferential = 0.0,
                    leftDrift = 1f, rightDrift = 1f,
                    longitudinalMotion = longitudinal, motionLength = deltaMovement.length(),
                    horizontalSpeed = deltaMovement.horizontalDistance(),
                    inFluid = isInFluidType, onGround = onGround(),
                    leftDamaged = leftWheelDamaged, rightDamaged = rightWheelDamaged,
                    engineDamaged = mainEngineDamaged, damageBias = steering.damageBias,
                    groundDamping = groundDamping, gravity = if (onGround() && !isInFluidType) computed().gravity else 0.0))
            },
            commitDrive = { next ->
                leftWheelRot = next.leftWheelRot
                rightWheelRot = next.rightWheelRot
                rudderRot = next.rudderRot
                yRot = next.yawDegrees
            },
            applyThrust = {
                if (isInFluidType || onGround()) {
                    deltaMovement = deltaMovement.add(getViewVector(1f)
                        .scale((if (drift()) 0.02 else 0.15) * targetSpeed * power))
                }
            },
        )
    }
}
