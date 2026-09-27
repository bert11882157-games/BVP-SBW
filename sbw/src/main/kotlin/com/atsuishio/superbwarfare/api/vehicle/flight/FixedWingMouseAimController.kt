package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Read-only surface sample, stable for one server step; the producer reuses its primitive storage. */
interface FixedWingSurfaceInput {
    val elevatorCommand: Double
    val aileronCommand: Double
    val rudderCommand: Double
    val groundRollAuthority: Double
    val groundYaw: Boolean
}

/** Contact hysteresis changes control authority only, never collision, lift, support, or motion. */
class FixedWingGroundControlGate(private val handling: FixedWingHandlingProfile) {
    val rotationReferenceSpeedMps: Double = handling.liftReferenceSpeedMps / sqrt(
        (min(18.0, handling.pitchProtectionAngleDegrees) * handling.normalizedLiftSlopePerDegree)
            .coerceAtMost(1.0),
    )
    var rollAuthority = 0.0
        private set
    var groundYaw = false
        private set
    private var initialized = false
    private var airborneTicks = 0
    private var rollEnabled = false

    fun reset() {
        initialized = false
        airborneTicks = 0
        rollEnabled = false
        rollAuthority = 0.0
        groundYaw = false
    }

    fun update(grounded: Boolean, forwardSpeedMps: Double, densityRatio: Double) {
        require(forwardSpeedMps.isFinite() && densityRatio.isFinite() && densityRatio > 0.0)
        if (!initialized) {
            groundYaw = grounded
            rollAuthority = if (grounded) 0.0 else 1.0
            initialized = true
        }
        if (grounded) {
            groundYaw = true
            airborneTicks = 0
        } else if (groundYaw) {
            airborneTicks = min(airborneTicks + 1, 3)
            if (airborneTicks == 3) groundYaw = false
        }
        val ratio = max(0.0, forwardSpeedMps) * sqrt(densityRatio) / rotationReferenceSpeedMps
        if (ratio >= 0.90) rollEnabled = true
        else if (ratio <= 0.85) rollEnabled = false
        val t = ((ratio - 0.85) / 0.15).coerceIn(0.0, 1.0)
        val target = if (!groundYaw) 1.0 else if (rollEnabled) t * t * (3.0 - 2.0 * t) else 0.0
        // Four authority units/second: contact and threshold edges cannot jump to full roll.
        rollAuthority += (target - rollAuthority).coerceIn(-0.20, 0.20)
    }
}

/**
 * Bounded direction-to-surface guidance, not an aircraft dynamics model.
 * Surface signs follow the solver body frame; screen-side roll is converted explicitly.
 */
class FixedWingMouseAimController(
    private val handling: FixedWingHandlingProfile,
) : FixedWingSurfaceInput {
    override var elevatorCommand = 0.0
        private set
    override var aileronCommand = 0.0
        private set
    override var rudderCommand = 0.0
        private set
    private val ground = FixedWingGroundControlGate(handling)
    override val groundRollAuthority: Double get() = ground.rollAuthority
    override val groundYaw: Boolean get() = ground.groundYaw
    var angularErrorDegrees = 0.0
        private set
    var pitchErrorDegrees = 0.0
        private set
    var yawErrorDegrees = 0.0
        private set
    private var positiveTurnPlane = true
    private var positiveLiftCapture = false
    private var manoeuvreRollAuthority = 0.0
    private var rollout = false
    private var rolloutAnchor: FixedWingPilotIntent? = null
    private var previousRateIntent: FixedWingPilotIntent? = null
    private var targetHeadingRate = 0.0
    private var targetElevationRate = 0.0
    private var targetHeldTicks = 0
    private var previousInversionRequested = false
    private var previousTaxiError = 0.0
    private var captured = false
    private var captureWeight = 0.0
    private var ordinaryCapture = false
    private var ordinaryCaptureWeight = 0.0
    private var screenGuidanceSeen = false
    private var previousIntent: FixedWingPilotIntent? = null
    private var captureUpX = 0.0
    private var captureUpY = 1.0
    private var captureUpZ = 0.0
    private var pitchWasManual = false
    private var rollWasManual = false
    private var pitchHandover = 0
    private var rollHandover = 0

    fun reset() {
        resetGuidance()
        ground.reset()
    }

    /** Centering changes guidance history, not the contact/airspeed authority lease. */
    fun resetGuidance() {
        elevatorCommand = 0.0
        aileronCommand = 0.0
        rudderCommand = 0.0
        angularErrorDegrees = 0.0
        pitchErrorDegrees = 0.0
        yawErrorDegrees = 0.0
        positiveTurnPlane = true
        positiveLiftCapture = false
        manoeuvreRollAuthority = 0.0
        rollout = false
        rolloutAnchor = null
        previousRateIntent = null
        targetHeadingRate = 0.0
        targetElevationRate = 0.0
        targetHeldTicks = 0
        previousInversionRequested = false
        previousTaxiError = 0.0
        captured = false
        captureWeight = 0.0
        ordinaryCapture = false
        ordinaryCaptureWeight = 0.0
        screenGuidanceSeen = false
        previousIntent = null
        captureUpX = 0.0
        captureUpY = 1.0
        captureUpZ = 0.0
        pitchWasManual = false
        rollWasManual = false
        pitchHandover = 0
        rollHandover = 0
    }

    fun update(
        model: FixedWingFlightModel,
        intent: FixedWingPilotIntent?,
        controlsEnabled: Boolean,
        grounded: Boolean,
        velocityX: Double, velocityY: Double, velocityZ: Double,
        densityRatio: Double,
    ): Boolean {
        val qx = model.quaternionX
        val qy = model.quaternionY
        val qz = model.quaternionZ
        val qw = model.quaternionW
        if (!qx.isFinite() || !qy.isFinite() || !qz.isFinite() || !qw.isFinite() ||
            !velocityX.isFinite() || !velocityY.isFinite() || !velocityZ.isFinite() ||
            !densityRatio.isFinite() || densityRatio !in 0.02..1.5) {
            reset()
            return false
        }
        val speedSquared = velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ
        val limit = max(handling.maximumSpeedMps, handling.hardSpeedLimitMps) * 8.0
        if (!speedSquared.isFinite() || speedSquared > limit * limit) {
            reset()
            return false
        }
        val rx = 1.0 - 2.0 * (qy * qy + qz * qz)
        val ry = 2.0 * (qx * qy + qz * qw)
        val rz = 2.0 * (qx * qz - qy * qw)
        val ux = 2.0 * (qx * qy - qz * qw)
        val uy = 1.0 - 2.0 * (qx * qx + qz * qz)
        val uz = 2.0 * (qy * qz + qx * qw)
        val fx = 2.0 * (qx * qz + qy * qw)
        val fy = 2.0 * (qy * qz - qx * qw)
        val fz = 1.0 - 2.0 * (qx * qx + qy * qy)
        val forwardSpeed = velocityX * fx + velocityY * fy + velocityZ * fz
        val upSpeed = velocityX * ux + velocityY * uy + velocityZ * uz
        val lateralSpeed = velocityX * rx + velocityY * ry + velocityZ * rz
        ground.update(grounded, forwardSpeed, densityRatio)
        val targetDiscontinuity = updateTargetRates(intent, controlsEnabled && !groundYaw)
        if (targetDiscontinuity || groundYaw || intent?.manualMask != 0) rollout = false
        if (!controlsEnabled || intent == null) {
            elevatorCommand = 0.0
            aileronCommand = 0.0
            rudderCommand = 0.0
            pitchWasManual = false
            rollWasManual = false
            pitchHandover = 0
            rollHandover = 0
            angularErrorDegrees = 0.0
            pitchErrorDegrees = 0.0
            yawErrorDegrees = 0.0
            return true
        }
        val previousAim = previousIntent
        // Compare with the acquisition anchor so a series of tiny mouse moves still retargets.
        val newAim = previousAim == null ||
            previousAim.directionX * intent.directionX + previousAim.directionY * intent.directionY +
            previousAim.directionZ * intent.directionZ < 0.9986295347545738
        if (newAim) {
            if (!rollout) captured = false
            previousIntent = intent
        }
        val screenRoll = intent.screenRollInput
        val rolloutTarget = rolloutAnchor
        // Capture may settle a held target, but cannot own a newly commanded lower manoeuvre.
        // The 0.2-degree world-target threshold accumulates from rollout entry, not each packet.
        val renewedLowerAim = rollout && rolloutTarget != null &&
            (rolloutTarget.directionX * intent.directionX +
                rolloutTarget.directionY * intent.directionY +
                rolloutTarget.directionZ * intent.directionZ < 0.9999939076577904)
        val inversionEdge = intent.inversionRequested && !previousInversionRequested
        previousInversionRequested = intent.inversionRequested
        if (screenRoll != null) screenGuidanceSeen = true
        val x = intent.directionX * rx + intent.directionY * ry + intent.directionZ * rz
        val y = intent.directionX * ux + intent.directionY * uy + intent.directionZ * uz
        val z = (intent.directionX * fx + intent.directionY * fy + intent.directionZ * fz)
            .coerceIn(-1.0, 1.0)
        val radial = sqrt(max(0.0, x * x + y * y))
        angularErrorDegrees = atan2(radial, z) * DEGREES
        if (radial > 1.0e-6) {
            pitchErrorDegrees = angularErrorDegrees * y / radial
            yawErrorDegrees = angularErrorDegrees * x / radial
        } else {
            // The antipode has no unique turn plane. Retain the prior pitch-plane choice.
            pitchErrorDegrees = if (z < 0.0) {
                if (positiveTurnPlane) 180.0 else -180.0
            } else 0.0
            yawErrorDegrees = 0.0
        }
        // The desired lift plane is the target projected perpendicular to the nose.
        // Unlike screen quadrants or local elevator sign, its gravity component remains
        // meaningful after climbing, banking through knife-edge or aiming behind the nose.
        val desiredLiftUp = if (radial > 1.0e-6) (intent.directionY - z * fy) / radial else 0.0
        val bank = abs(atan2(ry, uy) * DEGREES)
        val spatialLowerTurn = angularErrorDegrees > 25.0 && desiredLiftUp < -0.15 &&
            (z < -0.1 || bank > 60.0 || abs(fy) > 0.5)
        // Once a useful lower turn is underway, a changing screen projection must not
        // replace it with gravity-level capture mid-manoeuvre. New horizontal/up targets
        // still revoke it; reaching the held destination uses the normal capture cone.
        val continuingLowerTurn = positiveLiftCapture && !newAim &&
            angularErrorDegrees > 3.0 && intent.directionY < -sin(2.0 / DEGREES)
        val lowerManoeuvre = intent.inversionRequested || spatialLowerTurn || continuingLowerTurn
        if (groundYaw || !lowerManoeuvre) {
            if (positiveLiftCapture && !groundYaw) {
                rollout = true
                rolloutAnchor = intent
                captured = true
                val useGravity = 1.0 - intent.directionY * intent.directionY > 0.01
                captureUpX = if (useGravity) 0.0 else ux
                captureUpY = if (useGravity) 1.0 else uy
                captureUpZ = if (useGravity) 0.0 else uz
            }
            positiveLiftCapture = false
        } else if ((newAim || inversionEdge || renewedLowerAim || (!positiveLiftCapture && spatialLowerTurn)) &&
            screenRoll != null && angularErrorDegrees > 2.0 &&
            (spatialLowerTurn || ((y < -sin(2.0 / DEGREES) || renewedLowerAim) &&
                (abs(screenRoll) > 0.65 || pitchErrorDegrees < -20.0)))
        ) {
            positiveLiftCapture = true
            // Keep the acquired authority while the nose approaches its world target.
            // Otherwise shrinking screen travel can strand a half-completed inversion.
            // Reach useful authority by the 20-degree acquisition threshold even after
            // the nonlinear mouse curve; a weak latched limit can expire into capture
            // before the aircraft ever reaches its chosen positive-lift plane.
            manoeuvreRollAuthority = smoothUnit((angularErrorDegrees - 5.0) / 20.0)
            rollout = false
            rolloutAnchor = null
            captured = false
            captureWeight = 0.0
            ordinaryCapture = false
            ordinaryCaptureWeight = 0.0
        }
        var rollError = 0.0
        if (!groundYaw && angularErrorDegrees > 2.0 && radial > 1.0e-6) {
            val positive = atan2(x, y) * DEGREES
            val negative = wrap(positive + 180.0)
            // A small positive-load preference breaks ties; an 8-degree margin retains the choice.
            val positiveCost = abs(positive)
            val negativeCost = abs(negative) + 4.0
            // A profile unable to sustain one negative G cannot use an inverted turn plane
            // as an automatic capture route. Roll to the positive-load plane instead; manual
            // elevator commands and the kernel's physical load limits remain unchanged.
            if (positiveLiftCapture || handling.maximumNegativeLoadFactor < 1.0) positiveTurnPlane = true
            else if (positiveTurnPlane && negativeCost + 8.0 < positiveCost) positiveTurnPlane = false
            else if (!positiveTurnPlane && positiveCost + 8.0 < negativeCost) positiveTurnPlane = true
            rollError = if (positiveTurnPlane) positive else negative
        }
        val lowerTargetMoving = intent.inversionRequested &&
            (abs(targetHeadingRate) > 0.5 || abs(targetElevationRate) > 0.5)
        val heldTarget = targetHeldTicks >= 5 && abs(targetHeadingRate) < 0.5 &&
            abs(targetElevationRate) < 0.5
        if (!heldTarget && !rollout) captured = false
        // A banked lower-target equilibrium can retain a small slip error. Start the smooth
        // rollout inside its two-degree cone once input is held, instead of waiting forever.
        val captureCone = if (positiveLiftCapture) 2.0 else 1.0
        if (!captured && heldTarget && angularErrorDegrees <= captureCone &&
            (!positiveLiftCapture || !lowerTargetMoving)) {
            captured = true
            // Choose gravity-up once, outside the vertical cone; inside it retain the body plane.
            if (positiveLiftCapture) {
                positiveLiftCapture = false
                rollout = true
                rolloutAnchor = intent
            }
            val useGravity = 1.0 - intent.directionY * intent.directionY > 0.01
            captureUpX = if (useGravity) 0.0 else ux
            captureUpY = if (useGravity) 1.0 else uy
            captureUpZ = if (useGravity) 0.0 else uz
        } else if (angularErrorDegrees >= 3.0 && !rollout) captured = false
        val captureTarget = if (captured && !groundYaw) 1.0 else 0.0
        captureWeight += (captureTarget - captureWeight).coerceIn(-0.20, 0.20)
        if (captureWeight > 0.0) {
            // Parallel-transport the capture reference; do not flip world-up at a vertical crossing.
            val along = captureUpX * intent.directionX + captureUpY * intent.directionY +
                captureUpZ * intent.directionZ
            val tx = captureUpX - along * intent.directionX
            val ty = captureUpY - along * intent.directionY
            val tz = captureUpZ - along * intent.directionZ
            val length = sqrt(tx * tx + ty * ty + tz * tz)
            if (length > 1.0e-8) {
                captureUpX = tx / length
                captureUpY = ty / length
                captureUpZ = tz / length
                val localRight = captureUpX * rx + captureUpY * ry + captureUpZ * rz
                val localUp = captureUpX * ux + captureUpY * uy + captureUpZ * uz
                val levelError = atan2(localRight, localUp) * DEGREES
                rollError = blendAngle(rollError, levelError, captureWeight)
            }
        }
        val horizontalNose = max(0.0, 1.0 - fy * fy)
        val horizontalTarget = max(0.0, 1.0 - intent.directionY * intent.directionY)
        // Gentle central capture preserves fine mouse travel; outer travel still has full authority.
        val responseGain = if (groundYaw) 1.0 else 1.0 +
            (if (intent.firstPerson) 1.2 else 0.6) *
            smoothUnit((angularErrorDegrees - 2.0) / 6.0)
        val outerTravel = screenRoll?.let { smoothUnit((abs(it.toDouble()) - 0.65) / 0.35) } ?: 0.0
        val gain = min(1.25, handling.angularResponsePerSecond * 0.25) +
            (min(4.0, handling.angularResponsePerSecond * 0.25) -
                min(1.25, handling.angularResponsePerSecond * 0.25)) * outerTravel
        if (rollout && abs(atan2(-ry, uy) * DEGREES) < 2.0 &&
            abs(model.rollRateDegreesPerSecond) < 2.0) rollout = false
        val targetHorizontalLength = sqrt(horizontalTarget)
        val targetRateX = if (targetHorizontalLength > 0.24)
            (targetHeadingRate * intent.directionZ -
                targetElevationRate * intent.directionY * intent.directionX / targetHorizontalLength) / DEGREES else 0.0
        val targetRateY = targetElevationRate * targetHorizontalLength / DEGREES
        val targetRateZ = if (targetHorizontalLength > 0.24)
            (-targetHeadingRate * intent.directionX -
                targetElevationRate * intent.directionY * intent.directionZ / targetHorizontalLength) / DEGREES else 0.0
        val localTargetRateY = targetRateX * ux + targetRateY * uy + targetRateZ * uz
        val localTargetRateZ = targetRateX * fx + targetRateY * fy + targetRateZ * fz
        val targetPitchRate = ((z * localTargetRateY - y * localTargetRateZ) *
            DEGREES / max(1.0e-4, y * y + z * z))
            .coerceIn(-handling.gamePitchRateDegreesPerSecond, handling.gamePitchRateDegreesPerSecond)
        var ordinaryPitchRate = 0.0
        var ordinaryYawRate = 0.0
        if (!ordinaryCapture && !positiveLiftCapture && !groundYaw && (rollout || (uy > 0.0 && abs(x) > 1.0e-4)) &&
            horizontalNose > 0.5 && horizontalTarget > 0.5 && z > -0.8) {
            ordinaryCapture = true
        }
        if (positiveLiftCapture || groundYaw || horizontalNose < 0.06 ||
            horizontalTarget < 0.06 || z < -0.95) {
            ordinaryCapture = false
        }
        val ordinaryTarget = if (ordinaryCapture) {
            smoothUnit((horizontalNose - 0.06) / 0.44) *
                smoothUnit((horizontalTarget - 0.06) / 0.44) * smoothUnit((z + 0.95) / 0.15)
        } else 0.0
        ordinaryCaptureWeight += (ordinaryTarget - ordinaryCaptureWeight).coerceIn(-0.20, 0.20)
        if (ordinaryCaptureWeight > 0.0 && horizontalNose > 1.0e-8 && horizontalTarget > 1.0e-8) {
            // Ordinary heading capture targets an absolute bank about gravity-up. A relative
            // nose-capture pitch plane can continue requesting outward roll beyond knife-edge.
            val horizontalLength = sqrt(horizontalNose)
            val rightX = fz / horizontalLength
            val rightZ = -fx / horizontalLength
            val headingError = atan2(intent.directionX * rightX + intent.directionZ * rightZ,
                (intent.directionX * fx + intent.directionZ * fz) / horizontalLength) * DEGREES
            val wingSpeedSquared = forwardSpeed * forwardSpeed + upSpeed * upSpeed
            val normalizedLift = if (forwardSpeed > 0.0) {
                (handling.normalizedLiftSlopePerDegree *
                    min(handling.stallAngleDegrees * 0.95, handling.pitchProtectionAngleDegrees))
                    .coerceIn(0.0, 1.0) * (1.0 - 0.8 * model.stallSeverity)
            } else 0.0
            val availableLoad = handling.gameTurnLoadFactor(min(handling.maximumLoadFactor,
                densityRatio * wingSpeedSquared /
                    (handling.liftReferenceSpeedMps * handling.liftReferenceSpeedMps) *
                    normalizedLift))
            // Bank follows a world-heading rate, so increasing airspeed does not silently
            // lengthen the capture time constant. Load and roll braking still bound demand.
            val brakingMargin = abs(model.rollRateDegreesPerSecond) /
                handling.angularResponsePerSecond
            val loadBankLimit = min(85.0, acos(1.0 / max(1.0, availableLoad)) * DEGREES)
            val bankLimit = min(max(0.0, 80.0 - brakingMargin),
                loadBankLimit)
            val wingSpeed = max(handling.minimumControlSpeedMps, sqrt(wingSpeedSquared))
            val bank = atan2(-ry, uy).coerceIn(-85.0 / DEGREES, 85.0 / DEGREES)
            val rollRateAvailable = max(1.0, handling.gameRollRateDegreesPerSecond *
                model.controlEffectiveness) / DEGREES
            val bankStoppingHeading = if (rollout) 0.0 else sign(bank) * handling.gravityMps2 / wingSpeed /
                rollRateAvailable * -kotlin.math.ln(max(0.08, cos(bank))) * DEGREES
            val predictedHeadingError = headingError - bankStoppingHeading
            val desiredBank = atan((gain * predictedHeadingError + targetHeadingRate) / DEGREES *
                sqrt(wingSpeedSquared) / handling.gravityMps2)
                .coerceIn(-bankLimit / DEGREES, bankLimit / DEGREES)
            val gravityX = -fy * fx / horizontalLength
            val gravityY = horizontalLength
            val gravityZ = -fy * fz / horizontalLength
            val desiredX = gravityX * cos(desiredBank) + rightX * sin(desiredBank)
            val desiredY = gravityY * cos(desiredBank)
            val desiredZ = gravityZ * cos(desiredBank) + rightZ * sin(desiredBank)
            val absoluteError = atan2(desiredX * rx + desiredY * ry + desiredZ * rz,
                desiredX * ux + desiredY * uy + desiredZ * uz) * DEGREES
            rollError = blendAngle(rollError, absoluteError, ordinaryCaptureWeight)
            // Project a coordinated world-heading rate and independent elevation closure into
            // the actual body axes. A spherical pitch-plane error also requests a climb during
            // large horizontal turns, spending energy even when target elevation is unchanged.
            val horizontalRightDotBodyRightForRate = rightX * rx + rightZ * rz
            val horizontalRightDotBodyUpForRate = rightX * ux + rightZ * uz
            val currentHeadingRate = (model.pitchRateDegreesPerSecond *
                horizontalRightDotBodyUpForRate + model.yawRateDegreesPerSecond *
                horizontalRightDotBodyRightForRate) / horizontalLength
            val headingRate = if (forwardSpeed > handling.minimumControlSpeedMps) {
                targetHeadingRate + gain * headingError -
                    0.25 * (currentHeadingRate - targetHeadingRate)
            } else 0.0
            val elevationError = (asin(intent.directionY.coerceIn(-1.0, 1.0)) -
                asin(fy.coerceIn(-1.0, 1.0))) * DEGREES
            val elevationRate = targetElevationRate + gain * elevationError - 0.25 *
                ((model.pitchRateDegreesPerSecond * uy + model.yawRateDegreesPerSecond * ry) /
                    horizontalLength - targetElevationRate)
            val horizontalRightDotBodyRight = rightX * rx + rightZ * rz
            val horizontalRightDotBodyUp = rightX * ux + rightZ * uz
            ordinaryPitchRate = -headingRate * ry + elevationRate * horizontalRightDotBodyRight
            ordinaryYawRate = headingRate * uy - elevationRate * horizontalRightDotBodyUp
        }
        val pressure = ((densityRatio * (forwardSpeed * forwardSpeed + upSpeed * upSpeed) -
            handling.minimumControlSpeedMps * handling.minimumControlSpeedMps) /
            (handling.trimSpeedMps * handling.trimSpeedMps -
                handling.minimumControlSpeedMps * handling.minimumControlSpeedMps)).coerceIn(0.0, 1.0)
        val airflow = pressure * pressure * (3.0 - 2.0 * pressure)
        val effective = airflow * if (grounded) 1.0 else 1.0 - 0.65 * model.stallSeverity
        val pitchAirflow = handling.pitchAirflowAuthority(
            forwardSpeed * forwardSpeed + upSpeed * upSpeed, densityRatio)
        val rudderAuthority = handling.rudderRateDegreesPerSecond * FixedWingFlightModel.YAW_AUTHORITY_SCALE * effective
        val totalPressure = ((densityRatio * speedSquared -
            handling.minimumControlSpeedMps * handling.minimumControlSpeedMps) /
            (handling.trimSpeedMps * handling.trimSpeedMps -
                handling.minimumControlSpeedMps * handling.minimumControlSpeedMps))
            .coerceIn(0.0, 1.0)
        // Passive weathercock yaw is added by the force kernel after the rudder contribution.
        val passiveYawRate = (atan2(lateralSpeed,
            sqrt(forwardSpeed * forwardSpeed + upSpeed * upSpeed)) * DEGREES *
            handling.headingStabilityPerSecond)
            .coerceIn(-handling.rudderRateDegreesPerSecond, handling.rudderRateDegreesPerSecond) *
            totalPressure * totalPressure * (3.0 - 2.0 * totalPressure)
        if (ordinaryCaptureWeight > 0.0) {
            // A shared rate scale preserves the coordinated turn plane when one body axis
            // reaches its limit. Independent clipping would turn elevation into unwanted yaw.
            ordinaryPitchRate *= responseGain
            ordinaryYawRate *= responseGain
            var scale = if (abs(ordinaryPitchRate) > 1.0e-6) {
                min(1.0, handling.gamePitchRateDegreesPerSecond * pitchAirflow / abs(ordinaryPitchRate))
            } else 1.0
            if (abs(ordinaryYawRate) > 1.0e-6) {
                val a = (passiveYawRate - rudderAuthority) / ordinaryYawRate
                val b = (passiveYawRate + rudderAuthority) / ordinaryYawRate
                val lower = max(0.0, min(a, b))
                val feasible = min(scale, max(a, b)).coerceAtLeast(0.0)
                // A stalled rudder may be unable to cancel passive yaw at any scale. Preserve
                // elevator recovery in that case; the bounded rudder still opposes the drift.
                if (feasible >= lower && max(a, b) >= 0.0) scale = feasible
            }
            ordinaryPitchRate *= scale
            ordinaryYawRate *= scale
        }
        // Load limits belong to the force kernel. Choosing a positive-load roll plane must
        // not veto a mouse pitch correction while the aircraft banks toward that plane.
        val legacyPitchRate = (targetPitchRate + gain * pitchErrorDegrees -
            0.25 * (model.pitchRateDegreesPerSecond - targetPitchRate))
        val pitchFraction = normalizedRate(
            legacyPitchRate + (ordinaryPitchRate - legacyPitchRate) * ordinaryCaptureWeight,
            handling.gamePitchRateDegreesPerSecond * pitchAirflow,
        )
        val pitchResponseGain =
            if (pitchFraction * pitchErrorDegrees > 0.0)
                1.0 + (responseGain - 1.0) * (1.0 - ordinaryCaptureWeight) else 1.0
        val baselinePitchFraction = (pitchFraction * pitchResponseGain).coerceIn(-1.0, 1.0)
        // Predict local target error through body rotation and actuator response. An aligned
        // target should use elevator authority without waiting for a coordinated-level-turn rate.
        val predictionSeconds = (1.0 / handling.angularResponsePerSecond +
            0.5 / handling.elevatorTravelPerSecond).coerceIn(0.1, 0.5)
        val predictedY = y + localTargetRateY * predictionSeconds +
            (model.rollRateDegreesPerSecond * x -
            model.pitchRateDegreesPerSecond * z) * predictionSeconds / DEGREES
        val predictedZ = z + localTargetRateZ * predictionSeconds +
            (model.pitchRateDegreesPerSecond * y +
            model.yawRateDegreesPerSecond * x) * predictionSeconds / DEGREES
        val predictedPitchError = atan2(predictedY, predictedZ) * DEGREES
        val fullElevatorError = 22.0
        val predictivePitchFraction = if (pitchAirflow > 1.0e-6) {
            (targetPitchRate / (handling.gamePitchRateDegreesPerSecond * pitchAirflow) +
                predictedPitchError / fullElevatorError).coerceIn(-1.0, 1.0)
        } else 0.0
        val alignedPitchWeight = 1.0 - smoothUnit(abs(yawErrorDegrees) / 2.0)
        // After a circling manoeuvre, slip correction can consume the entire rudder.
        // Scaling both coordinated axes to that exhausted budget can then hold the jet
        // banked with almost no elevator despite a large elevation error. Keep useful
        // direct pitch recovery until the target elevation is recovered; level-heading
        // turns retain their coupled allocation and cannot acquire an unsolicited climb.
        val worldElevationError = abs(asin(intent.directionY.coerceIn(-1.0, 1.0)) -
            asin(fy.coerceIn(-1.0, 1.0))) * DEGREES
        val rudderLimitedRecovery = smoothUnit((worldElevationError - 2.0) / 6.0) *
            smoothUnit((abs(passiveYawRate - ordinaryYawRate) /
                max(1.0e-6, rudderAuthority) - 0.85) / 0.15)
        val predictiveWeight = if (groundYaw) 0.0 else
            smoothUnit((angularErrorDegrees - 2.0) / 3.0) *
                max(rudderLimitedRecovery, 1.0 - ordinaryCaptureWeight * (1.0 - alignedPitchWeight))
        val autoPitch = inversePitchCurve(baselinePitchFraction +
            (predictivePitchFraction - baselinePitchFraction) * predictiveWeight)
        // The target turn plane owns roll demand; do not keep rolling merely because a
        // screen marker remains off-centre after the nose has reached its world direction.
        val autoRoll =
            if (groundYaw || (screenGuidanceSeen && screenRoll == null)) 0.0
            else normalizedRate(
                gain * (rollError - model.rollRateDegreesPerSecond *
                    (1.0 / handling.angularResponsePerSecond +
                        0.5 * abs(model.aileron) / handling.aileronTravelPerSecond)),
                handling.gameRollRateDegreesPerSecond * effective,
            )
        val lateralRollGain = screenRoll?.let { 1.0 + smoothUnit(abs(it.toDouble()) / 0.65) } ?: 1.0
        val rollResponseGain = (1.0 + 0.25 * (responseGain - 1.0)) * lateralRollGain
        rudderCommand = if (groundYaw) {
            val horizontalAim = intent.directionX * intent.directionX + intent.directionZ * intent.directionZ
            val horizontalNose = fx * fx + fz * fz
            if (horizontalAim > 1.0e-8 && horizontalNose > 1.0e-8) {
                previousTaxiError = atan2(fz * intent.directionX - fx * intent.directionZ,
                    fx * intent.directionX + fz * intent.directionZ) * DEGREES
            }
            normalizedRate(gain * previousTaxiError - 0.25 * model.yawRateDegreesPerSecond,
                handling.taxiTurnRateDegreesPerSecond(forwardSpeed))
        } else {
            // Rudder sensitivity raised (0.25 -> 0.40 of the error gain and of the rudder budget) for a noticeably
            // quicker yaw response.
            val legacyYawRate =
                (0.40 * gain * yawErrorDegrees - 0.25 * model.yawRateDegreesPerSecond)
                    .coerceIn(-0.40 * rudderAuthority, 0.40 * rudderAuthority)
            val activeYawRate = legacyYawRate +
                (ordinaryYawRate - legacyYawRate) * ordinaryCaptureWeight
            normalizedRate(activeYawRate - passiveYawRate, rudderAuthority)
        }
        val mask = intent.manualMask
        val pitchManual = (mask and 3) != 0
        val rollManual = (mask and 12) != 0
        if (pitchManual) {
            elevatorCommand = bit(mask, FixedWingPilotIntent.PITCH_UP) - bit(mask, FixedWingPilotIntent.PITCH_DOWN)
            pitchHandover = 0
        } else {
            if (pitchWasManual) pitchHandover = 3
            elevatorCommand = handover(elevatorCommand, autoPitch, pitchHandover)
            if (pitchHandover > 0) pitchHandover--
        }
        if (rollManual) {
            aileronCommand = bit(mask, FixedWingPilotIntent.ROLL_RIGHT) - bit(mask, FixedWingPilotIntent.ROLL_LEFT)
            rollHandover = 0
        } else if (pitchManual) {
            // A manual pitch manoeuvre owns its turn plane; mouse guidance must not roll it over.
            aileronCommand = 0.0
            rollHandover = 0
        } else {
            if (rollWasManual || pitchWasManual) rollHandover = 3
            // Keep fine pitch corrections gentle, but give deliberate lateral travel full
            // authority by 65% of the radius rather than requiring the circle's exact edge.
            // A small floor retains wings-level recovery after the nose captures its target.
            val steeringLimit = screenRoll?.let { 0.08 + 0.92 * smoothUnit(abs(it.toDouble()) / 0.65) } ?: 1.0
            // Returning an already banked aircraft toward wings-level needs enough authority
            // to arrest its turn, including heavy transports with slow roll response.
            val levelError = atan2(ry, uy) * DEGREES
            val recoveryLimit = if (autoRoll * levelError > 0.0)
                smoothUnit((abs(levelError) - 15.0) / 45.0) else 0.0
            // A screen-vertical target can lie sideways in a banked aircraft's frame.
            // Permit acquisition of that turn plane instead of trapping recovery at the
            // centre-stick roll floor. Upright fine pitch remains on the gentle curve.
            val bankedTurnLimit = smoothUnit((abs(levelError) - 30.0) / 45.0) *
                smoothUnit((abs(yawErrorDegrees) - 3.0) / 12.0)
            val manoeuvreLimit = if (positiveLiftCapture) manoeuvreRollAuthority else 0.0
            val travelLimit = max(max(steeringLimit, manoeuvreLimit), max(recoveryLimit, bankedTurnLimit))
            // Aileron sensitivity: a little lower overall, and noticeably lower the further below the nose the
            // steering indicator sits, so a pitch-down command no longer throws the aircraft into a hard roll.
            // A deliberate inversion request keeps full authority.
            val belowNose = smoothUnit(-pitchErrorDegrees / 25.0)
            val aileronSensitivity = if (intent.inversionRequested) 1.0 else 0.85 * (1.0 - 0.45 * belowNose)
            val rollDemand = (autoRoll * rollResponseGain * aileronSensitivity).coerceIn(-travelLimit, travelLimit)
            aileronCommand = handover(aileronCommand, mouseRollResponse(rollDemand), rollHandover)
            if (rollHandover > 0) rollHandover--
        }
        pitchWasManual = pitchManual
        rollWasManual = rollManual
        return true
    }

    /** Consecutive 20 Hz samples, never the three-degree acquisition anchor. */
    private fun updateTargetRates(intent: FixedWingPilotIntent?, enabled: Boolean): Boolean {
        val previous = previousRateIntent
        previousRateIntent = intent.takeIf { enabled && it?.manualMask == 0 }
        if (!enabled || intent == null || intent.manualMask != 0 || previous == null) {
            targetHeldTicks = 0
            targetHeadingRate = 0.0
            targetElevationRate = 0.0
            return false
        }
        val dot = previous.directionX * intent.directionX +
            previous.directionY * intent.directionY + previous.directionZ * intent.directionZ
        targetHeldTicks = if (dot >= 0.9999999657305403) (targetHeldTicks + 1).coerceAtMost(20) else 0
        val discontinuity = dot < 0.9986295347545738
        if (discontinuity || 1.0 - previous.directionY * previous.directionY < 0.06 ||
            1.0 - intent.directionY * intent.directionY < 0.06) {
            targetHeadingRate = 0.0
            targetElevationRate = 0.0
            return discontinuity
        }
        val heading = (atan2(previous.directionZ * intent.directionX -
            previous.directionX * intent.directionZ, previous.directionX * intent.directionX +
            previous.directionZ * intent.directionZ) * DEGREES / 0.05)
            .coerceIn(-handling.gameRollRateDegreesPerSecond, handling.gameRollRateDegreesPerSecond)
        val elevation = ((asin(intent.directionY.coerceIn(-1.0, 1.0)) -
            asin(previous.directionY.coerceIn(-1.0, 1.0))) * DEGREES / 0.05)
            .coerceIn(-handling.gamePitchRateDegreesPerSecond, handling.gamePitchRateDegreesPerSecond)
        val blend = 1.0 - kotlin.math.exp(-handling.angularResponsePerSecond * 0.05)
        targetHeadingRate += (heading - targetHeadingRate) * blend
        targetElevationRate += (elevation - targetElevationRate) * blend
        return false
    }

    private fun inversePitchCurve(fraction: Double): Double {
        if (fraction == 0.0) return 0.0
        val linear = handling.pitchResponseLinearFraction
        val amount = abs(fraction)
        return sign(fraction) * 2.0 * amount /
            (linear + sqrt(linear * linear + 4.0 * (1.0 - linear) * amount))
    }

    private fun normalizedRate(rate: Double, available: Double): Double =
        if (available <= 1.0e-6) 0.0 else (rate / available).coerceIn(-1.0, 1.0)
    private fun smoothUnit(value: Double): Double {
        val t = value.coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
    private fun bit(mask: Int, bit: Int): Double = if ((mask and bit) != 0) 1.0 else 0.0
    private fun handover(previous: Double, next: Double, remaining: Int): Double =
        if (remaining <= 0) next else previous + (next - previous) / remaining
    private fun blendAngle(from: Double, to: Double, weight: Double): Double =
        wrap(from + wrap(to - from) * weight)
    private fun wrap(angle: Double): Double = ((angle + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    companion object {
        private const val DEGREES = 180.0 / PI

        /** Gentle centre response with the same sign and full endpoints; keyboard roll bypasses it. */
        internal fun mouseRollResponse(command: Double): Double = command * (0.50 + 0.50 * command * command)
    }
}
