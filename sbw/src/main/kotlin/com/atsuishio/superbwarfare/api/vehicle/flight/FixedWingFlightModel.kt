package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fixed-step aircraft dynamics in metres, seconds and degrees.
 * The caller supplies post-collision velocity and performs the sole world movement.
 * Internal attitude is a normalized quaternion; published angles use Minecraft conventions.
 */
class FixedWingFlightModel(
    val handling: FixedWingHandlingProfile = FixedWingHandlingProfile.GAME_JET,
) {
    var velocityX = 0.0
        private set
    var velocityY = 0.0
        private set
    var velocityZ = 0.0
        private set
    var yawDegrees = 0.0
        private set
    var pitchDegrees = 0.0
        private set
    var rollDegrees = 0.0
        private set
    var throttle = 0.0
        private set
    var elevator = 0.0
        private set
    var aileron = 0.0
        private set
    var rudder = 0.0
        private set
    var airbrake = 0.0
        private set
    var wheelBrakeActive = false
        private set
    private var effectiveEnginePower = 0.0
    private var gearDeployment = 0.0
    private var poweredGroundTicks = 0
    var afterburnerActive = false
        private set
    var stallActive = false
        private set
    var stallSeverity = 0.0
        private set
    var speedMps = 0.0
        private set
    var angleOfAttackDegrees = 0.0
        private set
    var forwardVelocityMps = 0.0
        private set
    var lateralVelocityMps = 0.0
        private set
    var verticalVelocityMps = 0.0
        private set
    var normalizedLift = 0.0
        private set
    var liftAccelerationMps2 = 0.0
        private set
    var dragAccelerationMps2 = 0.0
        private set
    var maneuverDragAccelerationMps2 = 0.0
        private set
    private var groundedForDrag = false
    var thrustAccelerationMps2 = 0.0
        private set
    var runwayLaunchMultiplier = 1.0
        private set
    var runwayContactAdmitted = false
        private set
    var runwayBaseHorizontalThrustMps2 = 0.0
        private set
    var controlEffectiveness = 0.0
        private set
    var pitchRateDegreesPerSecond = 0.0
        private set
    var rollRateDegreesPerSecond = 0.0
        private set
    var yawRateDegreesPerSecond = 0.0
        private set
    var airflowAuthority = 0.0
        private set
    var sideslipDegrees = 0.0
        private set
    var sideDragAccelerationMps2 = 0.0
        private set
    var overspeedDragAccelerationMps2 = 0.0
        private set
    var waveDragAccelerationMps2 = 0.0
        private set
    var jetThrustEnvironmentFraction = 1.0
        private set
    var preStepKineticEnergyPerKg = 0.0
        private set
    var postStepKineticEnergyPerKg = 0.0
        private set
    var stepThrustWorkPerKg = 0.0
        private set
    var stepGravityWorkPerKg = 0.0
        private set
    var stepDragWorkPerKg = 0.0
        private set
    var stepSideWorkPerKg = 0.0
        private set
    var stepLiftWorkPerKg = 0.0
        private set
    var stepGroundResistanceWorkPerKg = 0.0
        private set

    val quaternionX: Double get() = qx
    val quaternionY: Double get() = qy
    val quaternionZ: Double get() = qz
    val quaternionW: Double get() = qw
    val virtualPitchTarget: Double get() = pitchTarget
    val virtualRollTarget: Double get() = rollTarget

    private var qx = 0.0
    private var qy = 0.0
    private var qz = 0.0
    private var qw = 1.0
    private var rightX = 1.0
    private var rightY = 0.0
    private var rightZ = 0.0
    private var upX = 0.0
    private var upY = 1.0
    private var upZ = 0.0
    private var forwardX = 0.0
    private var forwardY = 0.0
    private var forwardZ = 1.0
    private var pitchTarget = 0.0
    private var rollTarget = 0.0
    private var pitchStick = 0.0
    private var rollStick = 0.0
    private var recoveryTicks = 0
    private var wingDropSign = 1.0
    private val forces = FixedWingForceIntegrator()
    private val afterburnerBoost = FixedWingAfterburnerBoost()
    private var diveFraction = 0.0
    private var densityRatio = 1.0
    private var worldSoundSpeedMps = 340.294 * handling.simulationLengthScale
    private var structuralTrueSpeedLimitMps = handling.maximumIndicatedSpeedMps
    val sampledDensityRatio: Double get() = densityRatio

    fun reset(yaw: Double, pitch: Double, roll: Double) {
        qx = 0.0
        qy = 0.0
        qz = 0.0
        qw = 1.0
        rotate(0.0, -finiteAngle(yaw) * RADIANS, 0.0)
        rotate(finiteAngle(pitch) * RADIANS, 0.0, 0.0)
        rotate(0.0, 0.0, finiteAngle(roll) * RADIANS)
        updateBasis()
        resetControls()
        pitchRateDegreesPerSecond = 0.0
        rollRateDegreesPerSecond = 0.0
        yawRateDegreesPerSecond = 0.0
        stallActive = false
        stallSeverity = 0.0
        recoveryTicks = 0
        velocityX = 0.0
        velocityY = 0.0
        velocityZ = 0.0
        resetTelemetry()
    }

    /** The terrain constraint accepts a physical ground pose without resetting pilot/engine state. */
    internal fun acceptGroundPitch(pitchDegrees: Double) {
        val yaw = yawDegrees
        val roll = rollDegrees
        qx = 0.0; qy = 0.0; qz = 0.0; qw = 1.0
        rotate(0.0, -yaw * RADIANS, 0.0)
        rotate(finiteAngle(pitchDegrees) * RADIANS, 0.0, 0.0)
        rotate(0.0, 0.0, roll * RADIANS)
        updateBasis()
        pitchRateDegreesPerSecond = 0.0
    }

    /** A controller change clears commands, not aerodynamic attitude or momentum. */
    @JvmOverloads
    fun resetControls(preserveThrottle: Boolean = false) {
        if (!preserveThrottle) throttle = 0.0
        pitchTarget = 0.0
        rollTarget = 0.0
        pitchStick = 0.0
        rollStick = 0.0
        elevator = 0.0
        aileron = 0.0
        rudder = 0.0
        airbrake = 0.0
        wheelBrakeActive = false
        effectiveEnginePower = 0.0
        poweredGroundTicks = 0
        afterburnerActive = false
        afterburnerBoost.reset()
        diveFraction = 0.0
    }

    /**
     * Explicit surfaces bypass the legacy virtual stick. Positive pitch is nose-up, positive
     * roll is right-bank, and positive rudder is nose-right. Legacy mouse targets return toward
     * zero with simulation time; engine failure does not disable aerodynamic surfaces.
     */
    @JvmOverloads
    fun step(
        serverTick: Long,
        inputVelocityX: Double,
        inputVelocityY: Double,
        inputVelocityZ: Double,
        grounded: Boolean,
        controlsEnabled: Boolean,
        throttleAxis: Double = 0.0,
        pitchDelta: Double = 0.0,
        rollDelta: Double = 0.0,
        rudderInput: Double = 0.0,
        airbrakeRequested: Boolean = false,
        afterburnerRequested: Boolean = false,
        recenterRequested: Boolean = false,
        engineAvailability: Double = 1.0,
        keyboardRollInput: Double = 0.0,
        airDensityRatio: Double = 1.0,
        airTemperatureKelvin: Double = 288.15,
        surfaces: FixedWingSurfaceInput? = null,
        gearDeployment: Double = 0.0,
        wheelsDeployed: Boolean = gearDeployment >= 1.0 - EPSILON,
        surfaceDamage: FixedWingSurfaceDamage = FixedWingSurfaceDamage.INTACT,
    ): Boolean {
        val vx = inputVelocityX
        val vy = inputVelocityY
        val vz = inputVelocityZ
        if (!gearDeployment.isFinite() || gearDeployment !in 0.0..1.0 ||
            !vx.isFinite() || !vy.isFinite() || !vz.isFinite() ||
            !throttleAxis.isFinite() || !pitchDelta.isFinite() || !rollDelta.isFinite() ||
            !rudderInput.isFinite() || !engineAvailability.isFinite() ||
            !keyboardRollInput.isFinite() || keyboardRollInput !in -1.0..1.0 ||
            !airDensityRatio.isFinite() || airDensityRatio !in 0.02..1.5 ||
            !airTemperatureKelvin.isFinite() || airTemperatureKelvin !in 160.0..330.0
        ) return failClosed()

        if (surfaces != null &&
            (!surfaces.elevatorCommand.isFinite() || surfaces.elevatorCommand !in -1.0..1.0 ||
                !surfaces.aileronCommand.isFinite() || surfaces.aileronCommand !in -1.0..1.0 ||
                !surfaces.rudderCommand.isFinite() || surfaces.rudderCommand !in -1.0..1.0 ||
                !surfaces.groundRollAuthority.isFinite() || surfaces.groundRollAuthority !in 0.0..1.0)
        ) return failClosed()

        this.gearDeployment = gearDeployment
        groundedForDrag = grounded
        densityRatio = airDensityRatio
        worldSoundSpeedMps =
            sqrt(1.4 * 287.05287 * airTemperatureKelvin) * handling.simulationLengthScale
        if (!worldSoundSpeedMps.isFinite() || worldSoundSpeedMps <= 0.0) return failClosed()
        structuralTrueSpeedLimitMps = FixedWingAtmosphere.trueSpeedForIndicatedLimit(
            handling.maximumIndicatedSpeedMps / handling.simulationLengthScale,
            airDensityRatio, airTemperatureKelvin,
        ) * handling.simulationLengthScale
        if (!structuralTrueSpeedLimitMps.isFinite()) return failClosed()

        val speedSquared = vx * vx + vy * vy + vz * vz
        if (!speedSquared.isFinite() ||
            speedSquared > square(max(handling.maximumSpeedMps, handling.hardSpeedLimitMps) * 8.0)
        ) return failClosed()
        val speed = sqrt(speedSquared)
        val previousForward = vx * forwardX + vy * forwardY + vz * forwardZ
        val previousLateral = vx * rightX + vy * rightY + vz * rightZ
        val previousUp = vx * upX + vy * upY + vz * upZ
        val wingSpeedSquared = square(previousForward) + square(previousUp)

        val center = recenterRequested || !controlsEnabled
        if (controlsEnabled) {
            val axis = throttleAxis.coerceIn(-1.0, 1.0)
            val spool = if (axis >= 0.0) {
                handling.spoolUpPerSecond
            } else {
                handling.spoolDownPerSecond
            }
            throttle = (throttle + axis * spool * DT).coerceIn(0.0, 1.0)
            if (surfaces == null && !center) {
                val decay = exp(-handling.automaticReturnPerSecond * DT)
                pitchTarget = (pitchTarget * decay +
                    pitchDelta.coerceIn(-512.0, 512.0) * handling.stickSensitivity)
                    .coerceIn(-1.0, 1.0)
                rollTarget = (rollTarget * decay +
                    rollDelta.coerceIn(-512.0, 512.0) * handling.stickSensitivity)
                    .coerceIn(-1.0, 1.0)
            }
        } else {
            throttle = max(0.0, throttle - handling.spoolDownPerSecond * DT)
        }
        if (center) {
            pitchTarget = 0.0
            rollTarget = 0.0
        }
        val stickResponse = response(
            if (center) handling.recenterResponsePerSecond else handling.stickResponsePerSecond,
        )
        val oldElevator = elevator
        val oldAileron = aileron
        val oldRudder = rudder
        if (surfaces != null) {
            // Directional flight control never reads or retains the legacy virtual stick.
            pitchTarget = 0.0
            rollTarget = 0.0
            pitchStick = 0.0
            rollStick = 0.0
            elevator = if (controlsEnabled) surfaces.elevatorCommand else 0.0
            aileron = if (controlsEnabled) surfaces.aileronCommand else 0.0
            rudder = if (controlsEnabled) surfaces.rudderCommand else 0.0
        } else {
            pitchStick += (pitchTarget - pitchStick) * stickResponse
            rollStick += (rollTarget - rollStick) * stickResponse
            elevator = deadzone(pitchStick)
            aileron = (deadzone(rollStick) +
                if (controlsEnabled) keyboardRollInput else 0.0).coerceIn(-1.0, 1.0)
            rudder = if (controlsEnabled) rudderInput.coerceIn(-1.0, 1.0) else 0.0
        }
        // Arbitration chooses a request; the actuator owns the physical deflection used by
        // both aerodynamic torque and published animation. Keyboard overrides do not bypass it.
        elevator = FixedWingSurfaceActuator.advance(oldElevator, elevator, handling.elevatorTravelPerSecond, DT)
        aileron = FixedWingSurfaceActuator.advance(oldAileron, aileron, handling.aileronTravelPerSecond, DT)
        rudder = FixedWingSurfaceActuator.advance(oldRudder, rudder, handling.rudderTravelPerSecond, DT)
        val engine = engineAvailability.coerceIn(0.0, 1.0)
        effectiveEnginePower = throttle * engine
        airbrake = FixedWingSurfaceActuator.advance(airbrake,
            if (controlsEnabled && airbrakeRequested && effectiveEnginePower <= EPSILON &&
                handling.airbrakeDragPerMetre > 0.0) 1.0 else 0.0, handling.elevatorTravelPerSecond, DT)
        wheelBrakeActive = grounded &&
            (!controlsEnabled || throttleAxis < 0.0 || airbrakeRequested)
        // Require consecutive powered rolling contacts. A touchdown/bounce sample cannot
        // acquire assistance, and liftoff, braking, reverse or controller loss clears it now.
        val poweredRolling = grounded && controlsEnabled && !wheelBrakeActive &&
            effectiveEnginePower > EPSILON && previousForward >= abs(previousLateral) &&
            wheelsDeployed
        poweredGroundTicks = if (poweredRolling) min(2, poweredGroundTicks + 1) else 0
        afterburnerActive = controlsEnabled && afterburnerRequested &&
            engine > 0.0 && throttle >= FixedWingAfterburnerControl.FULL_THROTTLE
        val engagementBoost = afterburnerBoost.update(afterburnerActive, serverTick)
        // Only real descending forward flight receives reduced resistance. Pitching down
        // while climbing, parked inputs and backward falls cannot acquire dive assistance.
        diveFraction = if (!grounded && previousForward > handling.minimumControlSpeedMps && speed > EPSILON) {
            min((-vy / speed).coerceIn(0.0, 1.0), (-forwardY).coerceIn(0.0, 1.0))
        } else 0.0
        val propellerReference = handling.propellerPowerReferenceSpeedMps
        val powerFraction = if (propellerReference > 0.0) {
            propellerReference / max(propellerReference, speed)
        } else {
            1.0
        }
        // A bounded inlet-speed correction, separate from spool and operational availability.
        // Reverse airflow does not receive a forward-ram thrust bonus.
        val inletMach = max(0.0, previousForward) / worldSoundSpeedMps
        val machBlend = boundedSquareBlend(inletMach)
        val highMachFactor = if (afterburnerActive) {
            handling.jetAfterburnerMachFactor
        } else {
            handling.jetDryMachFactor
        }
        val densityKnee = handling.jetThrustDensityKneeRatio
        val thrustDensityFraction = if (densityKnee > 0.0 && densityRatio < densityKnee) {
            densityKnee.pow(handling.jetThrustDensityExponent) * (densityRatio / densityKnee)
        } else {
            densityRatio.pow(handling.jetThrustDensityExponent)
        }
        jetThrustEnvironmentFraction = thrustDensityFraction *
            (1.0 + (highMachFactor - 1.0) * machBlend)
        thrustAccelerationMps2 = handling.gameDryAccelerationMps2 * throttle * engine *
            powerFraction * jetThrustEnvironmentFraction * handling.lowSpeedThrustMultiplier(speed) *
            (if (grounded) handling.launchThrustMultiplier(speed) else 1.0) *
            (1.0 - 0.15 * gearDeployment) *
            if (afterburnerActive) handling.gameAfterburnerMultiplier * engagementBoost else 1.0

        // Surface authority begins with usable airflow. Smooth pressure and stall response avoid
        // an attitude jump at either boundary; parked control deflections create no flight torque.
        airflowAuthority = pressureAuthority(wingSpeedSquared)
        controlEffectiveness = airflowAuthority *
            if (grounded) 1.0 else 1.0 - 0.65 * stallSeverity
        // Neutral pitch commands no body rotation; it must not impose upright lift when inverted.
        val previousAlpha = atan2(-previousUp, previousForward) / RADIANS
        val pitchAirflow = handling.pitchAirflowAuthority(wingSpeedSquared, densityRatio)
        val pitchAuthority = if (grounded || elevator * previousAlpha < 0.0) {
            pitchAirflow
        } else {
            pitchAirflow * (1.0 - 0.65 * stallSeverity)
        }
        val pitchCurve = handling.pitchResponseLinearFraction +
            (1.0 - handling.pitchResponseLinearFraction) * abs(elevator)
        val groundRotationAuthority = if (grounded) 1.0 - FixedWingGroundAttitude.settleWeight(speed,
            handling.takeoffHandling?.referenceSpeedMps ?: handling.liftReferenceSpeedMps) else 1.0
        val desiredPitch = limitPitchLoading(
            elevator * pitchCurve * handling.gamePitchRateDegreesPerSecond * pitchAuthority,
            grounded, previousForward, previousUp, previousLateral, wingSpeedSquared, previousAlpha,
        ) * surfaceDamage.pitchAuthority * groundRotationAuthority
        val desiredRoll = aileron * handling.gameRollRateDegreesPerSecond * controlEffectiveness *
            (surfaces?.groundRollAuthority ?: 1.0) * surfaceDamage.rollAuthority * groundRotationAuthority +
            (if (grounded) 0.0 else surfaceDamage.rollBiasDegreesPerSecond * controlEffectiveness) +
            (if (grounded) rollDegrees * 3.0 else 0.0) +
            (if (stallActive && !grounded) {
                wingDropSign * handling.wingDropDegreesPerSecond * stallSeverity * controlEffectiveness
            } else {
                0.0
            })
        // Wheel steering uses taxi speed independently of aerodynamic surface authority.
        val yawAuthority = if (grounded) {
            square(speed / handling.trimSpeedMps).coerceIn(0.12, 1.0)
        } else {
            controlEffectiveness
        }
        val sideslip = atan2(previousLateral, sqrt(wingSpeedSquared)) / RADIANS
        val yawStability = if (grounded) 0.0 else
            (sideslip * handling.headingStabilityPerSecond)
                .coerceIn(-handling.rudderRateDegreesPerSecond, handling.rudderRateDegreesPerSecond) *
                pressureAuthority(speedSquared)
        // Anticipate the gravity/roll contribution to sideslip during attached forward flight.
        // This coordinates body yaw; world momentum still changes only through the force solver.
        val coordinationLimit = handling.rudderRateDegreesPerSecond * controlEffectiveness
        val yawCoordination = if (!grounded && !stallActive &&
            previousForward > handling.minimumControlSpeedMps &&
            abs(atan2(-previousUp, previousForward)) < handling.stallAngleDegrees * RADIANS
        ) {
            ((-handling.gravityMps2 * rightY -
                rollRateDegreesPerSecond * RADIANS * previousUp) / previousForward / RADIANS)
                .coerceIn(-coordinationLimit, coordinationLimit)
        } else {
            0.0
        }
        val automaticYawLimit = handling.rudderRateDegreesPerSecond * pressureAuthority(speedSquared)
        // Directional guidance reports active yaw through rudder; only passive slip remains here.
        val automaticYaw = (if (surfaces == null) yawStability + yawCoordination else yawStability)
            .coerceIn(-automaticYawLimit, automaticYawLimit)
        val desiredYaw = if (grounded) {
            rudder * handling.taxiTurnRateDegreesPerSecond(previousForward)
        } else rudder * handling.rudderRateDegreesPerSecond * yawAuthority * surfaceDamage.yawAuthority + automaticYaw
        val angularResponse = response(handling.angularResponsePerSecond)
        // Small, zero-mean roll buffet shares the camera's speed envelope. Rotation about
        // the forward axis preserves upright/inverted force symmetry and pitch protection.
        val buffet = if (grounded) 0.0 else FixedWingSpeedEffects.buffetDegrees(speed, handling)
        val time = serverTick * DT
        pitchRateDegreesPerSecond +=
            (desiredPitch - pitchRateDegreesPerSecond) * angularResponse
        rollRateDegreesPerSecond +=
            (desiredRoll + buffet * sin(time * 5.3) - rollRateDegreesPerSecond) * angularResponse
        yawRateDegreesPerSecond +=
            (desiredYaw - yawRateDegreesPerSecond) * angularResponse
        // Rate-filter inertia must not carry a loading command through the protected AoA boundary.
        pitchRateDegreesPerSecond = limitPitchLoading(
            pitchRateDegreesPerSecond, grounded, previousForward, previousUp, previousLateral,
            wingSpeedSquared, previousAlpha,
        )
        if (grounded) {
            pitchRateDegreesPerSecond =
                boundedRate(-pitchDegrees, pitchRateDegreesPerSecond, 0.0, 18.0) * groundRotationAuthority
            rollRateDegreesPerSecond =
                boundedRate(-rollDegrees, rollRateDegreesPerSecond, -8.0, 8.0)
        }
        val groundYaw = surfaces?.groundYaw == true
        rotate(
            -pitchRateDegreesPerSecond * RADIANS * DT,
            if (groundYaw) 0.0 else yawRateDegreesPerSecond * RADIANS * DT,
            -rollRateDegreesPerSecond * RADIANS * DT,
        )
        if (groundYaw) rotate(0.0, yawRateDegreesPerSecond * RADIANS * DT, 0.0, world = true)
        updateBasis()

        forwardVelocityMps = vx * forwardX + vy * forwardY + vz * forwardZ
        lateralVelocityMps = vx * rightX + vy * rightY + vz * rightZ
        verticalVelocityMps = vx * upX + vy * upY + vz * upZ
        angleOfAttackDegrees = angleOfAttack(vx, vy, vz, speed)
        updateStall(grounded, speed, serverTick)
        val severityTarget = if (stallActive) {
            (0.25 + 0.75 * (abs(angleOfAttackDegrees) -
                handling.recoveryAngleDegrees) / 80.0).coerceIn(0.25, 1.0)
        } else {
            0.0
        }
        stallSeverity += (severityTarget - stallSeverity) * response(3.5)
        preStepKineticEnergyPerKg = speedSquared * 0.5
        forces.reset(vx, vy, vz)
        val substep = DT / FORCE_SUBSTEPS
        val halfStep = substep * 0.5
        val horizontalForward = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        val rollReference = max(50.0 / 3.6,
            handling.takeoffHandling?.referenceSpeedMps ?: handling.trimSpeedMps)
        val rollProgress = (speed / rollReference).coerceIn(0.0, 1.0)
        // Preserve the existing retractable-gear assist; fixed wheels only gain the 0–50 boost.
        val groundAssist = if (poweredGroundTicks == 2 && gearDeployment >= 1.0 - EPSILON &&
            horizontalForward > EPSILON) {
            9.0 * handling.launchGroundAssistMultiplier(speed) * thrustAccelerationMps2 *
                (1.0 - rollProgress * rollProgress * (3.0 - 2.0 * rollProgress))
        } else 0.0
        // Runway-only assistance is horizontal. It uses the existing kick/work ledger,
        // never a vertical launch impulse, velocity assignment, extra gravity or extra move.
        val assistScale = if (horizontalForward > EPSILON) groundAssist / horizontalForward else 0.0
        val thrustX = forwardX * (thrustAccelerationMps2 + assistScale)
        val thrustY = forwardY * thrustAccelerationMps2
        val thrustZ = forwardZ * (thrustAccelerationMps2 + assistScale)
        val runwayLaunch = poweredGroundTicks == 2 && speed < 50.0 / 3.6
        runwayContactAdmitted = poweredGroundTicks == 2
        runwayBaseHorizontalThrustMps2 = sqrt(thrustX * thrustX + thrustZ * thrustZ)
        runwayLaunchMultiplier = 1.0
        fun kickThrust() {
            // Double horizontal propulsion below 50 km/h on sustained wheel contact only.
            // Limit the extra impulse at each force substep so it cannot spill above the boundary.
            val horizontalThrust = sqrt(thrustX * thrustX + thrustZ * thrustZ)
            val remaining = 50.0 / 3.6 - sqrt(forces.x * forces.x + forces.z * forces.z)
            val boost = if (runwayLaunch && horizontalThrust > EPSILON) {
                (remaining / (horizontalThrust * halfStep) - 1.0).coerceIn(0.0, 1.0)
            } else 0.0
            runwayLaunchMultiplier += boost / (2.0 * FORCE_SUBSTEPS)
            forces.kick(thrustX * (1.0 + boost), thrustY, thrustZ * (1.0 + boost),
                handling.gravityMps2, halfStep)
        }
        repeat(FORCE_SUBSTEPS) {
            kickThrust()
            updateAerodynamicForces(forces.x, forces.y, forces.z)
            forces.drag(dragAccelerationMps2, halfStep)
            forces.sideDragLinear(handling.sideDragPerMetre * densityRatio, rightX, rightY, rightZ, halfStep)
            updateAerodynamicForces(forces.x, forces.y, forces.z)
            forces.lift(liftAccelerationMps2, rightX, rightY, rightZ, substep)
            forces.sideDragLinear(handling.sideDragPerMetre * densityRatio, rightX, rightY, rightZ, halfStep)
            updateAerodynamicForces(forces.x, forces.y, forces.z)
            forces.drag(dragAccelerationMps2, halfStep)
            kickThrust()
        }
        forces.limitSpeed(handling.hardSpeedLimitMps)
        velocityX = forces.x
        velocityY = forces.y
        velocityZ = forces.z
        val kineticBeforeGear = forces.speedSquared * 0.5
        if (grounded) applyLandingGearResistance((velocityY - vy) / DT)
        postStepKineticEnergyPerKg =
            (square(velocityX) + square(velocityY) + square(velocityZ)) * 0.5
        stepGroundResistanceWorkPerKg = postStepKineticEnergyPerKg - kineticBeforeGear
        stepThrustWorkPerKg = forces.thrustWork
        stepGravityWorkPerKg = forces.gravityWork
        stepDragWorkPerKg = forces.dragWork
        stepSideWorkPerKg = forces.sideWork
        stepLiftWorkPerKg = forces.liftWork
        updateAerodynamicForces(velocityX, velocityY, velocityZ)
        // The caller performs one movement/collision transaction, including downward ground demand.
        speedMps = sqrt(
            velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ,
        )
        if (!speedMps.isFinite() || !yawDegrees.isFinite() ||
            !pitchDegrees.isFinite() || !rollDegrees.isFinite()
        ) return failClosed()
        return true
    }

    /**
     * Bound further loading by attainable flight-path curvature and remaining AoA headroom.
     * Gravity, thrust and roll/yaw coupling are projected into body axes, including inverted flight.
     * This only reduces the requested rate: neutral never commands path following, and unloading
     * remains available during a stall. World velocity is changed exclusively by the force solver.
     */
    private fun limitPitchLoading(
        rate: Double, grounded: Boolean, forward: Double, up: Double, lateral: Double,
        wingSpeedSquared: Double, alphaDegrees: Double,
    ): Double {
        if (grounded || rate == 0.0 || rate * alphaDegrees < 0.0) return rate
        if (stallActive || abs(alphaDegrees) >= handling.stallAngleDegrees) return 0.0
        if (forward <= handling.minimumControlSpeedMps ||
            wingSpeedSquared <= square(handling.minimumControlSpeedMps)
        ) return rate
        val wingSpeed = sqrt(wingSpeedSquared)
        val direction = sign(rate)
        val bodyPathRate = (-handling.gravityMps2 * (forward * upY - up * forwardY) -
            thrustAccelerationMps2 * up +
            RADIANS * lateral * (rollRateDegreesPerSecond * forward - yawRateDegreesPerSecond * up)) /
            wingSpeedSquared
        val pathRate = signedLiftAcceleration(alphaDegrees, forward, wingSpeedSquared) / wingSpeed +
            bodyPathRate
        val protectedLift = direction * signedLiftAcceleration(
            direction * handling.pitchProtectionAngleDegrees, forward, wingSpeedSquared,
        )
        val curvatureLimit = protectedLift / wingSpeed + direction * bodyPathRate
        val headroomLimit = direction * pathRate + handling.pitchAoAResponsePerSecond *
            (handling.pitchProtectionAngleDegrees - direction * alphaDegrees) * RADIANS
        val allowed = max(0.0, min(curvatureLimit, headroomLimit)) / RADIANS
        return direction * min(abs(rate), allowed)
    }

    private fun requestedNormalizedLift(alpha: Double, forward: Double): Double =
        if (forward > 0.0) {
            (alpha * handling.normalizedLiftSlopePerDegree).coerceIn(-1.0, 1.0) *
                (1.0 - 0.8 * stallSeverity)
        } else {
            0.0
        }

    private fun signedLiftAcceleration(alpha: Double, forward: Double, wingSpeedSquared: Double): Double {
        val pressure = densityRatio * wingSpeedSquared / square(handling.liftReferenceSpeedMps)
        val requested = handling.gravityMps2 * pressure * requestedNormalizedLift(alpha, forward)
        val limited = requested.coerceIn(
            -handling.maximumNegativeLoadFactor * handling.gravityMps2,
            handling.maximumLoadFactor * handling.gravityMps2,
        )
        return if (groundedForDrag) limited
            else handling.gameTurnLoadFactor(limited / handling.gravityMps2) * handling.gravityMps2
    }

    private fun updateAerodynamicForces(vx: Double, vy: Double, vz: Double) {
        val speedSquared = square(vx) + square(vy) + square(vz)
        val speed = sqrt(speedSquared)
        forwardVelocityMps = vx * forwardX + vy * forwardY + vz * forwardZ
        lateralVelocityMps = vx * rightX + vy * rightY + vz * rightZ
        verticalVelocityMps = vx * upX + vy * upY + vz * upZ
        val wingSpeedSquared = square(forwardVelocityMps) + square(verticalVelocityMps)
        angleOfAttackDegrees = angleOfAttack(vx, vy, vz, speed)
        sideslipDegrees = atan2(lateralVelocityMps, sqrt(wingSpeedSquared)) / RADIANS
        val pressure = densityRatio * wingSpeedSquared / square(handling.liftReferenceSpeedMps)
        val accelerationPerNormalizedCl = handling.gravityMps2 * pressure
        liftAccelerationMps2 =
            signedLiftAcceleration(angleOfAttackDegrees, forwardVelocityMps, wingSpeedSquared)
        // Induced drag follows delivered lift, including the load-factor limit.
        normalizedLift = if (accelerationPerNormalizedCl > EPSILON) {
            liftAccelerationMps2 / accelerationPerNormalizedCl
        } else {
            0.0
        }
        val excessSpeed = max(0.0, speed - handling.softSpeedLimitMps)
        // Soft envelope resistance; never clamp or replace the velocity vector.
        overspeedDragAccelerationMps2 = (handling.overspeedResponsePerSecond * excessSpeed +
            handling.overspeedDragPerMetre * square(excessSpeed)) * (1.0 - 0.85 * diveFraction)
        // Passive transonic drag rise. Its coefficient is calibrated independently of the
        // safety-envelope resistance; no velocity replacement or extra integration occurs.
        waveDragAccelerationMps2 = if (handling.waveDragPerMetre > 0.0) {
            val excessMach = max(0.0, speed / worldSoundSpeedMps - handling.waveDragOnsetMach)
            densityRatio * handling.waveDragPerMetre * speedSquared *
                boundedSquareBlend(excessMach / handling.waveDragWidthMach)
        } else {
            0.0
        }
        // Diving sheds less speed to parasite/wave drag, so gravity can carry the aircraft
        // beyond the soft cap. Lift, airbrakes, stall drag and the final hard cap still apply.
        val diveDrag = 1.0 - 0.40 * diveFraction
        maneuverDragAccelerationMps2 = handling.maneuverDragMps2(speed, vy,
            rollRateDegreesPerSecond, groundedForDrag)
        dragAccelerationMps2 = densityRatio *
            (handling.parasiteDragPerMetre *
                (1.0 + handling.idleDragFactor * square(1.0 - effectiveEnginePower)) * diveDrag +
                handling.stallDragPerMetre * stallSeverity +
                handling.gameAirbrakeDragPerMetre * airbrake) * speedSquared +
            handling.inducedDragMps2 * square(normalizedLift) * pressure +
            waveDragAccelerationMps2 * diveDrag +
            overspeedDragAccelerationMps2 + maneuverDragAccelerationMps2
        sideDragAccelerationMps2 =
            handling.sideDragPerMetre * densityRatio * speed * abs(lateralVelocityMps)
    }

    private fun applyLandingGearResistance(netVerticalAcceleration: Double) {
        val horizontalLength = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        if (horizontalLength <= EPSILON) return
        val fx = forwardX / horizontalLength
        val fz = forwardZ / horizontalLength
        val longitudinal = velocityX * fx + velocityZ * fz
        val lateral = velocityX * fz - velocityZ * fx
        val load = (-netVerticalAcceleration / handling.gravityMps2).coerceIn(0.0, 1.0)
        val braking = if (wheelBrakeActive) {
            max(handling.groundBrakingMps2, 0.8 * handling.gravityMps2)
        } else 0.0
        val stop = min(abs(longitudinal),
            (handling.rollingResistanceMps2 + braking) * load * DT)
        val longitudinalChange = sign(longitudinal) * stop
        val lateralChange = lateral * response(handling.groundLateralResponsePerSecond * load)
        velocityX -= fx * longitudinalChange + fz * lateralChange
        velocityZ -= fz * longitudinalChange - fx * lateralChange
    }

    private fun updateStall(grounded: Boolean, speed: Double, serverTick: Long) {
        if (grounded || speed < handling.minimumStallStateSpeedMps) {
            stallActive = false
            recoveryTicks = 0
            return
        }
        if (!stallActive && abs(angleOfAttackDegrees) >= handling.stallAngleDegrees) {
            stallActive = true
            recoveryTicks = 0
            wingDropSign = if (abs(rollDegrees) > 2.0) {
                -sign(rollDegrees)
            } else {
                if ((serverTick and 1L) == 0L) 1.0 else -1.0
            }
        } else if (stallActive) {
            if (abs(angleOfAttackDegrees) <= handling.recoveryAngleDegrees &&
                densityRatio * speed * speed >= square(handling.recoverySpeedMps)
            ) {
                recoveryTicks++
                if (recoveryTicks >= handling.recoveryTicks) {
                    stallActive = false
                    recoveryTicks = 0
                }
            } else {
                recoveryTicks = 0
            }
        }
    }

    private fun angleOfAttack(vx: Double, vy: Double, vz: Double, speed: Double): Double {
        if (speed <= EPSILON) return 0.0
        val forward = vx * forwardX + vy * forwardY + vz * forwardZ
        val up = vx * upX + vy * upY + vz * upZ
        return atan2(-up, forward) / RADIANS
    }

    /** The fuselage can restore sideslip even when the wing has little usable forward airflow. */
    private fun pressureAuthority(speedSquared: Double): Double {
        val fraction = ((densityRatio * speedSquared - square(handling.minimumControlSpeedMps)) /
            (square(handling.trimSpeedMps) - square(handling.minimumControlSpeedMps))).coerceIn(0.0, 1.0)
        return fraction * fraction * (3.0 - 2.0 * fraction)
    }

    private fun rotate(x: Double, y: Double, z: Double, world: Boolean = false) {
        val angle = sqrt(x * x + y * y + z * z)
        if (angle <= EPSILON) return
        val factor = sin(angle * 0.5) / angle
        val dx = x * factor
        val dy = y * factor
        val dz = z * factor
        val dw = cos(angle * 0.5)
        val ax = if (world) dx else qx
        val ay = if (world) dy else qy
        val az = if (world) dz else qz
        val aw = if (world) dw else qw
        val bx = if (world) qx else dx
        val by = if (world) qy else dy
        val bz = if (world) qz else dz
        val bw = if (world) qw else dw
        qx = aw * bx + ax * bw + ay * bz - az * by
        qy = aw * by - ax * bz + ay * bw + az * bx
        qz = aw * bz + ax * by - ay * bx + az * bw
        qw = aw * bw - ax * bx - ay * by - az * bz
        val inverseNorm = 1.0 / sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        qx *= inverseNorm
        qy *= inverseNorm
        qz *= inverseNorm
        qw *= inverseNorm
    }

    private fun updateBasis() {
        rightX = 1.0 - 2.0 * (qy * qy + qz * qz)
        rightY = 2.0 * (qx * qy + qz * qw)
        rightZ = 2.0 * (qx * qz - qy * qw)
        upX = 2.0 * (qx * qy - qz * qw)
        upY = 1.0 - 2.0 * (qx * qx + qz * qz)
        upZ = 2.0 * (qy * qz + qx * qw)
        forwardX = 2.0 * (qx * qz + qy * qw)
        forwardY = 2.0 * (qy * qz - qx * qw)
        forwardZ = 1.0 - 2.0 * (qx * qx + qy * qy)
        yawDegrees = atan2(-forwardX, forwardZ) / RADIANS
        pitchDegrees = -asin(forwardY.coerceIn(-1.0, 1.0)) / RADIANS
        rollDegrees = atan2(rightY, upY) / RADIANS
    }

    private fun deadzone(value: Double): Double =
        if (abs(value) <= handling.stickDeadzone) 0.0 else
            sign(value) * (abs(value) - handling.stickDeadzone) / (1.0 - handling.stickDeadzone)

    private fun boundedSquareBlend(value: Double): Double =
        if (value <= 1.0) {
            val squared = value * value
            squared / (1.0 + squared)
        } else {
            val inverse = 1.0 / value
            1.0 / (1.0 + inverse * inverse)
        }

    private fun boundedRate(angle: Double, rate: Double, low: Double, high: Double): Double =
        if (rate >= 0.0) {
            if (angle >= high) 0.0 else min(rate, (high - angle) / DT)
        } else {
            if (angle <= low) 0.0 else max(rate, (low - angle) / DT)
        }

    private fun failClosed(): Boolean {
        resetControls()
        velocityX = 0.0
        velocityY = -handling.gravityMps2 * DT
        velocityZ = 0.0
        resetTelemetry()
        speedMps = abs(velocityY)
        return false
    }

    private fun resetTelemetry() {
        speedMps = 0.0
        angleOfAttackDegrees = 0.0
        forwardVelocityMps = 0.0
        lateralVelocityMps = 0.0
        verticalVelocityMps = 0.0
        normalizedLift = 0.0
        liftAccelerationMps2 = 0.0
        dragAccelerationMps2 = 0.0
        maneuverDragAccelerationMps2 = 0.0
        thrustAccelerationMps2 = 0.0
        runwayLaunchMultiplier = 1.0
        runwayContactAdmitted = false
        runwayBaseHorizontalThrustMps2 = 0.0
        controlEffectiveness = 0.0
        airflowAuthority = 0.0
        sideslipDegrees = 0.0
        sideDragAccelerationMps2 = 0.0
        overspeedDragAccelerationMps2 = 0.0
        waveDragAccelerationMps2 = 0.0
        jetThrustEnvironmentFraction = 1.0
        preStepKineticEnergyPerKg = 0.0
        postStepKineticEnergyPerKg = 0.0
        stepThrustWorkPerKg = 0.0
        stepGravityWorkPerKg = 0.0
        stepDragWorkPerKg = 0.0
        stepSideWorkPerKg = 0.0
        stepLiftWorkPerKg = 0.0
        stepGroundResistanceWorkPerKg = 0.0
    }

    private fun finiteAngle(value: Double): Double = if (value.isFinite()) wrap(value) else 0.0
    private fun response(perSecond: Double): Double = 1.0 - exp(-perSecond * DT)
    private fun square(value: Double): Double = value * value
    private fun wrap(value: Double): Double {
        val reduced = value % 360.0
        return when {
            reduced > 180.0 -> reduced - 360.0
            reduced <= -180.0 -> reduced + 360.0
            else -> reduced
        }
    }

    companion object {
        const val DT = 0.05
        private const val EPSILON = 1.0E-9
        private const val FORCE_SUBSTEPS = 5
        private const val RADIANS = PI / 180.0
    }
}
