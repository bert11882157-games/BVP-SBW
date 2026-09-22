package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic fixed-wing/jet flight owner.  It is deliberately independent of the helicopter
 * force model: one server tick samples air-relative motion, integrates bounded aerodynamic and
 * engine forces, and returns the final motion/attitude for VehicleEntity's existing single move.
 * No world queries, entity scans, or client-side authority occur here.
 */
open class FixedWingFlightStrategy(
    val profile: FixedWingFlightProfile,
) : VehicleFlightStrategy() {
    override val strategyKind: VehicleFlightStrategyKind = VehicleFlightStrategyKind.FIXED_WING

    private var initialized = false
    private var lastServerTick = Long.MIN_VALUE
    private var throttle = 0.0
    private var afterburnerRemaining: Double? = null
    private var yawDegrees = 0.0
    private var pitchDegrees = 0.0
    private var rollDegrees = 0.0
    private var yawRate = 0.0
    private var pitchRate = 0.0
    private var rollRate = 0.0
    /** Mouse commands are integrated as bounded pilot stick positions, not body-axis snaps. */
    private var pitchStick = 0.0
    private var rollStick = 0.0
    private var stallActive = false
    private var stallRecoveryTicks = 0
    private var wingDropSign = 1.0
    private var lastSpeedMps = 0.0
    private var lastAngleOfAttackDegrees = 0.0
    private var lastAfterburnerActive = false
    private var lastForwardVelocityMps = 0.0
    private var lastLateralVelocityMps = 0.0
    private var lastVerticalVelocityMps = 0.0
    private var lastDynamicPressurePa = 0.0
    private var lastLiftCoefficient = 0.0
    private var lastDragCoefficient = 0.0
    private var lastLiftForceNewtons = 0.0
    private var lastDragForceNewtons = 0.0
    private var lastThrustForceNewtons = 0.0
    private var lastGravityAccelerationMps2 = 0.0
    private var lastControlEffectiveness = 0.0
    private var lastStallSeverity = 0.0

    override fun onActivated(vehicle: VehicleEntity) {
        reset(vehicle, null)
    }

    override fun onDeactivated(vehicle: VehicleEntity) {
        initialized = false
        lastServerTick = Long.MIN_VALUE
        throttle = 0.0
        pitchStick = 0.0
        rollStick = 0.0
        yawRate = 0.0
        pitchRate = 0.0
        rollRate = 0.0
        stallActive = false
        stallRecoveryTicks = 0
    }

    override fun tickServer(
        vehicle: VehicleEntity,
        input: VehicleFlightInputContext,
    ): VehicleFlightTickResult {
        if (!initialized ||
            (lastServerTick != Long.MIN_VALUE && input.serverTick - lastServerTick != 1L) ||
            hasExternalAttitudeDiscontinuity(vehicle)
        ) {
            reset(vehicle, input)
        }
        initialized = true
        lastServerTick = input.serverTick

        // The movement sample is the post-collision velocity from the preceding transaction.
        // Keeping it as the integration seed preserves momentum and lets collision response feed
        // back into the next authoritative tick without a second move or a velocity snap.
        val vx = finiteOrZero(input.airVelocity.x)
        val vy = finiteOrZero(input.airVelocity.y)
        val vz = finiteOrZero(input.airVelocity.z)
        val speedBlocksPerTick = sqrt(vx * vx + vy * vy + vz * vz)
        val speedMps = speedBlocksPerTick * TICKS_PER_SECOND
        lastSpeedMps = speedMps

        val yawRad = yawDegrees * DEG_TO_RAD
        val pitchRad = pitchDegrees * DEG_TO_RAD
        val rollRad = rollDegrees * DEG_TO_RAD
        val sinYaw = sin(yawRad)
        val cosYaw = cos(yawRad)
        val sinPitch = sin(pitchRad)
        val cosPitch = cos(pitchRad)

        // Minecraft's forward convention is -sin(yaw), -sin(pitch), cos(yaw).
        val forwardX = -sinYaw * cosPitch
        val forwardY = -sinPitch
        val forwardZ = cosYaw * cosPitch
        val rightBaseX = cosYaw
        val rightBaseZ = sinYaw
        val upBaseX = forwardY * rightBaseZ
        val upBaseY = forwardZ * rightBaseX - forwardX * rightBaseZ
        val upBaseZ = -forwardY * rightBaseX
        val cosRoll = cos(rollRad)
        val sinRoll = sin(rollRad)
        val rightX = rightBaseX * cosRoll + upBaseX * sinRoll
        val rightY = upBaseY * sinRoll
        val rightZ = rightBaseZ * cosRoll + upBaseZ * sinRoll
        val upX = upBaseX * cosRoll - rightBaseX * sinRoll
        val upY = upBaseY * cosRoll
        val upZ = upBaseZ * cosRoll - rightBaseZ * sinRoll

        val forwardVelocity = vx * forwardX + vy * forwardY + vz * forwardZ
        val rightVelocity = vx * rightX + vy * rightY + vz * rightZ
        val upVelocity = vx * upX + vy * upY + vz * upZ
        lastForwardVelocityMps = forwardVelocity * TICKS_PER_SECOND
        lastLateralVelocityMps = rightVelocity * TICKS_PER_SECOND
        lastVerticalVelocityMps = upVelocity * TICKS_PER_SECOND
        val aoaDegrees = if (speedBlocksPerTick > EPSILON) {
            clamp(
                atan2(-upVelocity, max(forwardVelocity, EPSILON)) * RAD_TO_DEG,
                -profile.maximumAoADegrees,
                profile.maximumAoADegrees,
            )
        } else {
            0.0
        }
        lastAngleOfAttackDegrees = aoaDegrees

        updateStallState(input, speedMps, aoaDegrees)
        val stallSeverity = if (stallActive) {
            clamp(
                (abs(aoaDegrees) - profile.stallAoADegrees) /
                    max(profile.maximumAoADegrees - profile.stallAoADegrees, EPSILON),
                0.0,
                1.0,
            )
        } else {
            0.0
        }
        lastStallSeverity = stallSeverity
        val liftFactor = if (stallActive) max(0.18, 1.0 - 0.75 * stallSeverity) else 1.0
        val controlDynamicPressure = 0.5 * AIR_DENSITY * speedMps * speedMps
        val controlFactor =
            clamp(
                controlDynamicPressure / profile.controlEffectivenessDynamicPressurePa,
                profile.lowSpeedControlFraction,
                1.0,
            ) *
                (if (stallActive) profile.stallControlFraction else 1.0)
        lastControlEffectiveness = controlFactor

        val angleOfAttackRadians = aoaDegrees * DEG_TO_RAD
        val liftCoefficient = clamp(
            profile.liftSlopePerRadian * angleOfAttackRadians,
            -profile.maxLiftCoefficient,
            profile.maxLiftCoefficient,
        ) * liftFactor
        val dragCoefficient = profile.zeroLiftDragCoefficient +
            profile.inducedDragFactor * liftCoefficient * liftCoefficient +
            profile.stallDragCoefficient * stallSeverity
        lastLiftCoefficient = liftCoefficient
        lastDragCoefficient = dragCoefficient

        var nextVx = vx
        var nextVy = vy
        var nextVz = vz
        var liftRight = 0.0
        if (speedBlocksPerTick > EPSILON) {
            val velocityX = vx / speedBlocksPerTick
            val velocityY = vy / speedBlocksPerTick
            val velocityZ = vz / speedBlocksPerTick
            val upDotVelocity = upX * velocityX + upY * velocityY + upZ * velocityZ
            var liftX = upX - upDotVelocity * velocityX
            var liftY = upY - upDotVelocity * velocityY
            var liftZ = upZ - upDotVelocity * velocityZ
            val liftLength = sqrt(liftX * liftX + liftY * liftY + liftZ * liftZ)
            if (liftLength > EPSILON) {
                liftX /= liftLength
                liftY /= liftLength
                liftZ /= liftLength
                liftRight = liftX * rightX + liftY * rightY + liftZ * rightZ
            }

            val dynamicPressure = 0.5 * AIR_DENSITY * speedMps * speedMps
            lastDynamicPressurePa = dynamicPressure
            lastLiftForceNewtons = dynamicPressure * profile.referenceWingAreaM2 * liftCoefficient
            lastDragForceNewtons = dynamicPressure * profile.referenceWingAreaM2 * dragCoefficient
            val liftAcceleration = lastLiftForceNewtons /
                profile.massKg / ACCELERATION_TO_TICK_DELTA
            val dragAcceleration = lastDragForceNewtons /
                profile.massKg / ACCELERATION_TO_TICK_DELTA
            nextVx += liftX * liftAcceleration - velocityX * dragAcceleration
            nextVy += liftY * liftAcceleration - velocityY * dragAcceleration
            nextVz += liftZ * liftAcceleration - velocityZ * dragAcceleration
        } else {
            lastDynamicPressurePa = 0.0
            lastLiftForceNewtons = 0.0
            lastDragForceNewtons = 0.0
        }

        val occupiedControls = input.occupied && !input.wreck
        val throttleAxis = if (occupiedControls) {
            clamp(input.fixedWingThrottleAxis, -1.0, 1.0)
        } else {
            0.0
        }
        // W/S is a persistent spool command: releasing both keys holds the current throttle,
        // while S winds it down.  No tank engine-power sample can seed a fixed-wing throttle.
        if (abs(throttleAxis) > profile.joystickDeadzone) {
            val spoolRate = if (throttleAxis > 0.0) {
                profile.throttleSpoolUpPerSecond
            } else {
                profile.throttleSpoolDownPerSecond
            }
            throttle = clamp(
                throttle + throttleAxis * spoolRate * SECONDS_PER_TICK,
                0.0,
                1.0,
            )
        } else if (!occupiedControls) {
            throttle = 0.0
        }

        val afterburnerActive = occupiedControls && profile.afterburnerEnabled &&
            input.fixedWingAfterburnerRequested &&
            (afterburnerRemaining == null || afterburnerRemaining!! > 0.0)
        lastAfterburnerActive = afterburnerActive
        if (afterburnerActive && afterburnerRemaining != null && profile.afterburnerConsumptionPerSecond > 0.0) {
            afterburnerRemaining = max(
                0.0,
                afterburnerRemaining!! - profile.afterburnerConsumptionPerSecond * SECONDS_PER_TICK,
            )
        }
        val thrustMultiplier = if (afterburnerActive) profile.afterburnerMultiplier else 1.0
        lastThrustForceNewtons = profile.dryThrustNewtons * throttle * thrustMultiplier
        val thrustAcceleration = lastThrustForceNewtons /
            profile.massKg / ACCELERATION_TO_TICK_DELTA
        nextVx += forwardX * thrustAcceleration
        nextVy += forwardY * thrustAcceleration
        nextVz += forwardZ * thrustAcceleration

        // Gravity is accounted for here and marked in the result; VehicleEntity therefore skips
        // its generic gravity addition for this transaction.  Ground contact only arrests the
        // downward component after gravity has been evaluated, preserving takeoff lift.
        lastGravityAccelerationMps2 = -abs(input.gravityPerTick) * ACCELERATION_TO_TICK_DELTA
        nextVy -= abs(input.gravityPerTick)

        if (input.onGround) {
            val groundLongitudinal = nextVx * forwardX + nextVz * forwardZ
            val groundLateral = nextVx * rightX + nextVz * rightZ
            val lateralDamping = clamp(profile.groundFrictionPerSecond * SECONDS_PER_TICK, 0.0, 0.95)
            // Rolling resistance is a coasting/braking term, not a hard speed governor. The
            // previous fixed damping multiplied every powered tick and settled the 5730 kg
            // MiG-19 at roughly 6 m/s, so it could never reach its stall/takeoff speed. Let
            // propulsion overcome the passive term smoothly as throttle spools up; reverse or
            // unpowered motion still receives the authored ground friction response.
            val propulsionGroundComponent = thrustAcceleration * (forwardX * forwardX + forwardZ * forwardZ)
            val rollingRate = profile.groundFrictionPerSecond * 0.25 * SECONDS_PER_TICK
            val rollingDamping = if (
                !input.fixedWingAirbrakeRequested &&
                    propulsionGroundComponent > EPSILON &&
                    groundLongitudinal * propulsionGroundComponent >= 0.0
            ) {
                clamp(rollingRate * (1.0 - throttle), 0.0, 0.8)
            } else {
                clamp(rollingRate, 0.0, 0.8)
            }
            val brakeDamping = if (input.fixedWingAirbrakeRequested) {
                clamp(profile.groundBrakingPerSecond * SECONDS_PER_TICK, 0.0, 0.95)
            } else {
                0.0
            }
            val dampedLateral = groundLateral * (1.0 - lateralDamping)
            val dampedLongitudinal = groundLongitudinal * (1.0 - max(rollingDamping, brakeDamping))
            nextVx += rightX * (dampedLateral - groundLateral) + forwardX * (dampedLongitudinal - groundLongitudinal)
            nextVz += rightZ * (dampedLateral - groundLateral) + forwardZ * (dampedLongitudinal - groundLongitudinal)
            if (nextVy < 0.0) nextVy = 0.0

            val groundSpeed = sqrt(nextVx * nextVx + nextVz * nextVz)
            val groundLimit = profile.groundSpeedLimitMps / TICKS_PER_SECOND
            if (groundSpeed > groundLimit && groundSpeed > EPSILON) {
                val scale = groundLimit / groundSpeed
                nextVx *= scale
                nextVz *= scale
            }
        }

        if (input.inFluid) {
            // Do not query fluid blocks or add a second movement path; this is a bounded drag
            // response for a collision result already supplied by the host vehicle.
            nextVx *= 0.82
            nextVy *= 0.82
            nextVz *= 0.82
        }

        if (occupiedControls) {
            pitchStick = updateJoystick(input.fixedWingMousePitchDelta, pitchStick)
            rollStick = updateJoystick(input.fixedWingMouseRollDelta, rollStick)
        } else {
            pitchStick = 0.0
            rollStick = 0.0
        }
        val angularControl = if (occupiedControls) controlFactor else 0.0
        val commandedPitchRate = pitchStick *
            profile.pitchAuthorityDegPerSecondSquared * angularControl
        val commandedRollRate = rollStick *
            profile.rollAuthorityDegPerSecondSquared * angularControl
        val commandedYawRate = clamp(input.fixedWingRudderInput, -1.0, 1.0) *
            profile.yawAuthorityDegPerSecondSquared * angularControl
        pitchRate += commandedPitchRate * SECONDS_PER_TICK
        rollRate += commandedRollRate * SECONDS_PER_TICK
        yawRate += commandedYawRate * SECONDS_PER_TICK
        yawRate += liftRight * profile.bankTurnAuthorityDegPerSecondSquared * angularControl * SECONDS_PER_TICK
        if (speedBlocksPerTick > EPSILON) {
            val slip = clamp(rightVelocity / speedBlocksPerTick, -1.0, 1.0)
            yawRate -= slip * profile.yawSlipStabilityPerSecond * angularControl * SECONDS_PER_TICK
        }
        pitchRate *= dampingFactor(profile.pitchDampingPerSecond)
        rollRate *= dampingFactor(profile.rollDampingPerSecond)
        yawRate *= dampingFactor(profile.yawDampingPerSecond)
        if (stallActive) {
            rollRate += wingDropSign * profile.wingDropRateDegPerSecondSquared *
                (0.35 + 0.65 * stallSeverity) * SECONDS_PER_TICK
        }

        pitchDegrees = clamp(pitchDegrees + pitchRate * SECONDS_PER_TICK, -89.0, 89.0)
        rollDegrees = clamp(rollDegrees + rollRate * SECONDS_PER_TICK, -89.0, 89.0)
        yawDegrees = wrapDegrees(yawDegrees + yawRate * SECONDS_PER_TICK)

        var speedAfterForces = sqrt(nextVx * nextVx + nextVy * nextVy + nextVz * nextVz)
        val envelopeBlocksPerTick = profile.worldSpeedEnvelopeMps / TICKS_PER_SECOND
        if (envelopeBlocksPerTick > EPSILON && speedAfterForces > envelopeBlocksPerTick) {
            val excessFraction = clamp(
                (speedAfterForces - envelopeBlocksPerTick) / envelopeBlocksPerTick,
                0.0,
                1.0,
            )
            val envelopeDamping = clamp(
                excessFraction * profile.worldSpeedEnvelopeResponsePerSecond * SECONDS_PER_TICK,
                0.0,
                0.75,
            )
            val envelopeScale = 1.0 - envelopeDamping
            nextVx *= envelopeScale
            nextVy *= envelopeScale
            nextVz *= envelopeScale
            speedAfterForces = sqrt(nextVx * nextVx + nextVy * nextVy + nextVz * nextVz)
        }
        val maximumSpeedBlocksPerTick = min(profile.maxStructuralSpeedMps, profile.maxEngineSpeedMps) /
            TICKS_PER_SECOND
        if (speedAfterForces > maximumSpeedBlocksPerTick && speedAfterForces > EPSILON) {
            val scale = maximumSpeedBlocksPerTick / speedAfterForces
            nextVx *= scale
            nextVy *= scale
            nextVz *= scale
        }

        if (!finiteState(nextVx, nextVy, nextVz, yawDegrees, pitchDegrees, rollDegrees)) {
            reset(vehicle, input)
            return safeResult(input)
        }

        return VehicleFlightTickResult(
            motion = Vec3(nextVx, nextVy, nextVz),
            // Existing instrument channels are retained as generic fixed-wing telemetry.  The
            // wire schema is unchanged; no client channel is used to authorize flight motion.
            rotorLift = liftCoefficient,
            collective = aoaDegrees,
            thrust = profile.dryThrustNewtons * throttle * thrustMultiplier,
            throttle = throttle,
            bodyYaw = yawDegrees.toFloat(),
            bodyPitch = pitchDegrees.toFloat(),
            bodyRoll = rollDegrees.toFloat(),
            motionIncludesGravity = true,
        )
    }

    /** Returns an immutable state object on demand; no state object is allocated during a tick. */
    fun stateSnapshot(): FixedWingFlightState = FixedWingFlightState(
        serverTick = lastServerTick,
        speedMps = lastSpeedMps,
        angleOfAttackDegrees = lastAngleOfAttackDegrees,
        throttle = throttle,
        afterburnerActive = lastAfterburnerActive,
        stallActive = stallActive,
        bodyYawDegrees = yawDegrees,
        bodyPitchDegrees = pitchDegrees,
        bodyRollDegrees = rollDegrees,
        forwardVelocityMps = lastForwardVelocityMps,
        lateralVelocityMps = lastLateralVelocityMps,
        verticalVelocityMps = lastVerticalVelocityMps,
        dynamicPressurePa = lastDynamicPressurePa,
        liftCoefficient = lastLiftCoefficient,
        dragCoefficient = lastDragCoefficient,
        liftForceNewtons = lastLiftForceNewtons,
        dragForceNewtons = lastDragForceNewtons,
        thrustForceNewtons = lastThrustForceNewtons,
        gravityAccelerationMps2 = lastGravityAccelerationMps2,
        controlEffectiveness = lastControlEffectiveness,
        stallSeverity = lastStallSeverity,
        yawRateDegPerSecond = yawRate,
        pitchRateDegPerSecond = pitchRate,
        rollRateDegPerSecond = rollRate,
        profileId = profile.id,
        massKg = profile.massKg,
        maxStructuralSpeedMps = profile.maxStructuralSpeedMps,
        maxEngineSpeedMps = profile.maxEngineSpeedMps,
        stallSpeedMps = profile.stallSpeedMps,
        stallAoADegrees = profile.stallAoADegrees,
        worldSpeedEnvelopeMps = profile.worldSpeedEnvelopeMps,
        worldSpeedEnvelopeResponsePerSecond = profile.worldSpeedEnvelopeResponsePerSecond,
        joystickSensitivity = profile.joystickSensitivity,
        joystickDeadzone = profile.joystickDeadzone,
        joystickSmoothingPerSecond = profile.joystickSmoothingPerSecond,
        joystickReturnPerSecond = profile.joystickReturnPerSecond,
    )

    private fun updateStallState(input: VehicleFlightInputContext, speedMps: Double, aoaDegrees: Double) {
        if (!stallActive && speedMps <= profile.stallSpeedMps && abs(aoaDegrees) >= profile.stallAoADegrees) {
            stallActive = true
            stallRecoveryTicks = 0
            // A latched sign gives a repeatable wing drop without an unseeded random source.
            wingDropSign = if ((input.serverTick and 1L) == 0L) 1.0 else -1.0
        } else if (stallActive) {
            if (speedMps >= profile.stallRecoverySpeedMps && abs(aoaDegrees) <= profile.stallRecoveryAoADegrees) {
                stallRecoveryTicks++
                if (stallRecoveryTicks >= profile.stallRecoveryTicks) {
                    stallActive = false
                    stallRecoveryTicks = 0
                }
            } else {
                stallRecoveryTicks = 0
            }
        }
    }

    private fun reset(vehicle: VehicleEntity, input: VehicleFlightInputContext?) {
        // Do not assign the motion back to the entity here; only seed the next result.
        initialized = true
        lastServerTick = input?.serverTick ?: Long.MIN_VALUE
        // Fixed-wing throttle is owned by its W/S spool state; never seed it from a tank/legacy
        // engine-power sample when the strategy is activated or re-seeded after a discontinuity.
        throttle = 0.0
        afterburnerRemaining = profile.afterburnerFuelSeconds
        yawDegrees = finiteOrZero(input?.bodyYawDegrees ?: vehicle.yRot.toDouble())
        pitchDegrees = clamp(finiteOrZero(input?.bodyPitchDegrees ?: vehicle.xRot.toDouble()), -89.0, 89.0)
        rollDegrees = clamp(finiteOrZero(input?.bodyRollDegrees ?: vehicle.roll.toDouble()), -89.0, 89.0)
        yawRate = 0.0
        pitchRate = 0.0
        rollRate = 0.0
        pitchStick = 0.0
        rollStick = 0.0
        stallActive = false
        stallRecoveryTicks = 0
        wingDropSign = 1.0
        lastSpeedMps = 0.0
        lastAngleOfAttackDegrees = 0.0
        lastAfterburnerActive = false
        lastForwardVelocityMps = 0.0
        lastLateralVelocityMps = 0.0
        lastVerticalVelocityMps = 0.0
        lastDynamicPressurePa = 0.0
        lastLiftCoefficient = 0.0
        lastDragCoefficient = 0.0
        lastLiftForceNewtons = 0.0
        lastDragForceNewtons = 0.0
        lastThrustForceNewtons = 0.0
        lastGravityAccelerationMps2 = 0.0
        lastControlEffectiveness = 0.0
        lastStallSeverity = 0.0
    }

    private fun hasExternalAttitudeDiscontinuity(vehicle: VehicleEntity): Boolean {
        val yawDelta = abs(wrapDegrees(vehicle.yRot.toDouble() - yawDegrees))
        val pitchDelta = abs(vehicle.xRot.toDouble() - pitchDegrees)
        val rollDelta = abs(vehicle.roll.toDouble() - rollDegrees)
        return yawDelta > ATTITUDE_RESET_DEGREES ||
            pitchDelta > ATTITUDE_RESET_DEGREES ||
            rollDelta > ATTITUDE_RESET_DEGREES
    }

    private fun safeResult(input: VehicleFlightInputContext): VehicleFlightTickResult {
        val x = finiteOrZero(input.airVelocity.x)
        val y = finiteOrZero(input.airVelocity.y)
        val z = finiteOrZero(input.airVelocity.z)
        return VehicleFlightTickResult(
            Vec3(x, y, z),
            0.0,
            0.0,
            0.0,
            0.0,
            yawDegrees.toFloat(),
            pitchDegrees.toFloat(),
            rollDegrees.toFloat(),
            true,
        )
    }

    private fun dampingFactor(perSecond: Double): Double =
        1.0 - clamp(perSecond * SECONDS_PER_TICK, 0.0, 1.0)

    private fun updateJoystick(rawDelta: Double, current: Double): Double {
        val boundedDelta = clamp(finiteOrZero(rawDelta), -MAX_MOUSE_DELTA, MAX_MOUSE_DELTA)
        val commandDelta = boundedDelta * profile.joystickSensitivity
        if (abs(commandDelta) <= profile.joystickDeadzone) {
            val returnResponse = 1.0 - exp(-profile.joystickReturnPerSecond * SECONDS_PER_TICK)
            return clamp(current + (0.0 - current) * returnResponse, -1.0, 1.0)
        }
        val target = clamp(current + commandDelta, -1.0, 1.0)
        val response = 1.0 - exp(-profile.joystickSmoothingPerSecond * SECONDS_PER_TICK)
        return clamp(current + (target - current) * response, -1.0, 1.0)
    }

    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun wrapDegrees(value: Double): Double {
        var wrapped = value % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped <= -180.0) wrapped += 360.0
        return wrapped
    }

    private fun clamp(value: Double, minimum: Double, maximum: Double): Double =
        value.coerceIn(minimum, maximum)

    private fun finiteState(
        velocityX: Double,
        velocityY: Double,
        velocityZ: Double,
        yaw: Double,
        pitch: Double,
        bodyRoll: Double,
    ): Boolean = velocityX.isFinite() && velocityY.isFinite() && velocityZ.isFinite() &&
        yaw.isFinite() && pitch.isFinite() && bodyRoll.isFinite()

    companion object {
        private const val TICKS_PER_SECOND = 20.0
        private const val SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND
        private const val ACCELERATION_TO_TICK_DELTA = TICKS_PER_SECOND * TICKS_PER_SECOND
        private const val AIR_DENSITY = 1.225
        private const val EPSILON = 1.0E-9
        private const val DEG_TO_RAD = PI / 180.0
        private const val RAD_TO_DEG = 180.0 / PI
        private const val ATTITUDE_RESET_DEGREES = 45.0
        private const val MAX_MOUSE_DELTA = 512.0
    }
}
