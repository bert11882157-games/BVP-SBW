package com.atsuishio.superbwarfare.api.vehicle.flight

/** Immutable diagnostic/state snapshot; the hot tick path keeps the same values as primitives. */
data class FixedWingFlightState(
    val serverTick: Long,
    val speedMps: Double,
    val angleOfAttackDegrees: Double,
    val throttle: Double,
    val afterburnerActive: Boolean,
    val stallActive: Boolean,
    val bodyYawDegrees: Double,
    val bodyPitchDegrees: Double,
    val bodyRollDegrees: Double,
    val forwardVelocityMps: Double = 0.0,
    val lateralVelocityMps: Double = 0.0,
    val verticalVelocityMps: Double = 0.0,
    val dynamicPressurePa: Double = 0.0,
    val liftCoefficient: Double = 0.0,
    val dragCoefficient: Double = 0.0,
    val liftForceNewtons: Double = 0.0,
    val dragForceNewtons: Double = 0.0,
    val thrustForceNewtons: Double = 0.0,
    val gravityAccelerationMps2: Double = 0.0,
    val controlEffectiveness: Double = 0.0,
    val stallSeverity: Double = 0.0,
    val yawRateDegPerSecond: Double = 0.0,
    val pitchRateDegPerSecond: Double = 0.0,
    val rollRateDegPerSecond: Double = 0.0,
    val profileId: String = "",
    val massKg: Double = 0.0,
    val maxStructuralSpeedMps: Double = 0.0,
    val maxEngineSpeedMps: Double = 0.0,
    val stallSpeedMps: Double = 0.0,
    val stallAoADegrees: Double = 0.0,
    val worldSpeedEnvelopeMps: Double = 0.0,
    val worldSpeedEnvelopeResponsePerSecond: Double = 0.0,
    val joystickSensitivity: Double = 0.0,
    val joystickDeadzone: Double = 0.0,
    val joystickSmoothingPerSecond: Double = 0.0,
    val joystickReturnPerSecond: Double = 0.0,
    val quaternionX: Double = 0.0,
    val quaternionY: Double = 0.0,
    val quaternionZ: Double = 0.0,
    val quaternionW: Double = 1.0,
    val virtualPitchTarget: Double = 0.0,
    val virtualRollTarget: Double = 0.0,
    val airflowAuthority: Double = 0.0,
    val sideslipDegrees: Double = 0.0,
    val signedLiftAccelerationMps2: Double = 0.0,
    val dragAccelerationMps2: Double = 0.0,
    val sideDragAccelerationMps2: Double = 0.0,
    val overspeedDragAccelerationMps2: Double = 0.0,
    val thrustAccelerationMps2: Double = 0.0,
    val preStepKineticEnergyPerKg: Double = 0.0,
    val postStepKineticEnergyPerKg: Double = 0.0,
    val stepThrustWorkPerKg: Double = 0.0,
    val stepGravityWorkPerKg: Double = 0.0,
    val stepDragWorkPerKg: Double = 0.0,
    val stepSideWorkPerKg: Double = 0.0,
    val stepLiftWorkPerKg: Double = 0.0,
    val stepGroundResistanceWorkPerKg: Double = 0.0,
) {
    init {
        require(speedMps.isFinite() && speedMps >= 0.0) { "speedMps must be finite and nonnegative" }
        require(angleOfAttackDegrees.isFinite()) { "angleOfAttackDegrees must be finite" }
        require(throttle.isFinite() && throttle in 0.0..1.0) { "throttle must be finite in [0,1]" }
        require(bodyYawDegrees.isFinite() && bodyPitchDegrees.isFinite() && bodyRollDegrees.isFinite()) {
            "body attitude must be finite"
        }
        val diagnosticValues = doubleArrayOf(
            forwardVelocityMps,
            lateralVelocityMps,
            verticalVelocityMps,
            dynamicPressurePa,
            liftCoefficient,
            dragCoefficient,
            liftForceNewtons,
            dragForceNewtons,
            thrustForceNewtons,
            gravityAccelerationMps2,
            controlEffectiveness,
            stallSeverity,
            yawRateDegPerSecond,
            pitchRateDegPerSecond,
            rollRateDegPerSecond,
            massKg,
            maxStructuralSpeedMps,
            maxEngineSpeedMps,
            stallSpeedMps,
            stallAoADegrees,
            worldSpeedEnvelopeMps,
            worldSpeedEnvelopeResponsePerSecond,
            joystickSensitivity,
            joystickDeadzone,
            joystickSmoothingPerSecond,
            joystickReturnPerSecond,
            quaternionX, quaternionY, quaternionZ, quaternionW,
            virtualPitchTarget, virtualRollTarget, airflowAuthority, sideslipDegrees,
            signedLiftAccelerationMps2, dragAccelerationMps2, sideDragAccelerationMps2,
            overspeedDragAccelerationMps2, thrustAccelerationMps2,
            preStepKineticEnergyPerKg, postStepKineticEnergyPerKg,
            stepThrustWorkPerKg, stepGravityWorkPerKg, stepDragWorkPerKg,
            stepSideWorkPerKg, stepLiftWorkPerKg, stepGroundResistanceWorkPerKg,
        )
        require(diagnosticValues.all(Double::isFinite)) { "fixed-wing diagnostics must be finite" }
        require(dynamicPressurePa >= 0.0 && liftForceNewtons.isFinite() && dragForceNewtons.isFinite()) {
            "aerodynamic diagnostics must be finite"
        }
        require(controlEffectiveness >= 0.0 && controlEffectiveness <= 1.0) {
            "controlEffectiveness must be in [0,1]"
        }
        require(virtualPitchTarget in -1.0..1.0 && virtualRollTarget in -1.0..1.0 &&
            airflowAuthority in 0.0..1.0) { "virtual controls and airflow authority are out of range" }
        require(stallSeverity >= 0.0 && stallSeverity <= 1.0) {
            "stallSeverity must be in [0,1]"
        }
        require(massKg >= 0.0 && maxStructuralSpeedMps >= 0.0 && maxEngineSpeedMps >= 0.0 && stallSpeedMps >= 0.0) {
            "fixed-wing limits must be nonnegative"
        }
        require(worldSpeedEnvelopeMps >= 0.0 && worldSpeedEnvelopeResponsePerSecond >= 0.0) {
            "fixed-wing speed envelope diagnostics must be nonnegative"
        }
        require(joystickSensitivity >= 0.0 && joystickDeadzone in 0.0..1.0 &&
            joystickSmoothingPerSecond >= 0.0 && joystickReturnPerSecond >= 0.0) {
            "fixed-wing joystick diagnostics are out of range"
        }
    }
}
