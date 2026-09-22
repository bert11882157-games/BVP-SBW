package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import org.joml.Math

/**
 * Owns the existing tracked and wheeled ground-propulsion steps.
 *
 * World, passenger, power, particle, and kinematic state remain on [VehicleEntity]; this
 * service only preserves the established ordered mutation sequence behind the entity facade.
 */
internal class VehicleGroundMotionService(private val vehicle: VehicleEntity) {
    /**
     * Limits only the ground-wheel steering component to the lateral acceleration
     * already implied by the wheel branch's retained-speed damping.  The damping
     * value is a per-tick velocity retention, so (1 - retention) * gravity is a
     * bounded traction proxy; no new authored steering datum or force is added.
     */
    private fun limitGroundWheelYaw(
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

        val passenger0 = getFirstPassenger()

        if (!hasOperationalPower(energyCost)) {
            forwardInputDown = false
            backInputDown = false
            leftInputDown = false
            rightInputDown = false
            power *= 0.95f
        }

        if (passenger0 == null) {
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

        if (level() is ServerLevel) {
            consumeOperationalPower(energyCost)
        }

        if (drift()) {
            steeringSpeed *= 3.4f
        }

        deltaRot *= Math.max(0.76f - 0.1f * deltaMovement.horizontalDistance(), 0.3).toFloat()

        val s0 = deltaMovement.dot(getViewVector(1f))

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

        if (isInFluidType || onGround()) {
            deltaMovement = deltaMovement.add(getViewVector(1f).scale((if (drift()) 0.03 else 0.15) * targetSpeed * power))
        }
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

        val passenger0 = getFirstPassenger()

        if (!hasOperationalPower(energyCost)) {
            forwardInputDown = false
            backInputDown = false
            leftInputDown = false
            rightInputDown = false
            power *= 0.95f
            deltaRot *= 0.5f
        }

        if (passenger0 == null) {
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

        if (level is ServerLevel) {
            consumeOperationalPower(energyCost)
        }

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

        deltaRot *= Math.max(0.78f - 0.25f * deltaMovement.horizontalDistance(), 0.1).toFloat()

        val s0 = deltaMovement.dot(getViewVector(1f))

        leftWheelRot = ((leftWheelRot - wheelRotSpeed * s0) - Mth.clamp(
            wheelDifferential * deltaRot, -5.0, 5.0
        ) * deltaMovement.length()).toFloat()
        rightWheelRot = ((rightWheelRot - wheelRotSpeed * s0) + Mth.clamp(
            wheelDifferential * deltaRot, -5.0, 5.0
        ) * deltaMovement.length()).toFloat()

        rudderRot = Mth.clamp(
            rudderRot - deltaRot,
            -0.8f,
            0.8f
        ) * 0.75f

        var steeringYaw = Math.max(
            (if (isInFluidType && !onGround()) 6 else 12) * deltaMovement
                .horizontalDistance(), 0.0
        ) * rudderRot * (if (power > 0) 1 else -1)
        if (onGround() && !isInFluidType) {
            steeringYaw = limitGroundWheelYaw(
                steeringYaw,
                deltaMovement.horizontalDistance(),
                groundDamping,
                computed().gravity
            )
        }

        yRot = (yRot - steeringYaw - i * s0).toFloat()

        if ((isInFluidType || onGround())) {
            deltaMovement = deltaMovement.add(getViewVector(1f).scale((if (drift()) 0.02 else 0.15) * targetSpeed * power))
        }
    }

}

