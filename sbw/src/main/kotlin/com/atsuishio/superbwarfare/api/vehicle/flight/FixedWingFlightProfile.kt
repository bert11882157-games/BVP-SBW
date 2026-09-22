package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Immutable SI-unit tuning surface for a fixed-wing/jet flight owner.
 *
 * Force values are newtons, mass is kilograms, area is square metres and speeds are metres per
 * second.  Angular authority/damping values are degrees per second squared/second.  The strategy
 * converts SI acceleration to SBW blocks-per-tick without adding another movement authority.
 */
data class FixedWingFlightProfile(
    val id: String,
    val massKg: Double,
    val referenceWingAreaM2: Double,
    val liftSlopePerRadian: Double,
    val maxLiftCoefficient: Double,
    val zeroLiftDragCoefficient: Double,
    val inducedDragFactor: Double,
    val stallDragCoefficient: Double,
    val stallAoADegrees: Double,
    val stallRecoveryAoADegrees: Double,
    val stallSpeedMps: Double,
    val stallRecoverySpeedMps: Double,
    val maximumAoADegrees: Double,
    val dryThrustNewtons: Double,
    val afterburnerEnabled: Boolean,
    val afterburnerMultiplier: Double,
    val afterburnerFuelSeconds: Double?,
    val afterburnerConsumptionPerSecond: Double,
    val maxStructuralSpeedMps: Double,
    val maxEngineSpeedMps: Double,
    val groundSpeedLimitMps: Double,
    val pitchAuthorityDegPerSecondSquared: Double,
    val pitchDampingPerSecond: Double,
    val rollAuthorityDegPerSecondSquared: Double,
    val rollDampingPerSecond: Double,
    val yawAuthorityDegPerSecondSquared: Double,
    val yawDampingPerSecond: Double,
    val controlEffectivenessDynamicPressurePa: Double,
    val lowSpeedControlFraction: Double,
    val stallControlFraction: Double,
    val bankTurnAuthorityDegPerSecondSquared: Double,
    val yawSlipStabilityPerSecond: Double,
    val groundFrictionPerSecond: Double,
    val groundBrakingPerSecond: Double,
    val throttleSpoolUpPerSecond: Double,
    val throttleSpoolDownPerSecond: Double,
    val wingDropRateDegPerSecondSquared: Double,
    val stallRecoveryTicks: Int,
    /** Secondary world-speed envelope used to keep the opt-in strategy within its design regime. */
    val worldSpeedEnvelopeMps: Double = 90.0,
    val worldSpeedEnvelopeResponsePerSecond: Double = 2.5,
    /** Mouse-to-stick response is intentionally profile-owned and bounded, not a camera API. */
    val joystickSensitivity: Double = 0.02,
    val joystickDeadzone: Double = 0.03,
    val joystickSmoothingPerSecond: Double = 12.0,
    val joystickReturnPerSecond: Double = 6.0,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        positive("massKg", massKg)
        positive("referenceWingAreaM2", referenceWingAreaM2)
        nonNegative("liftSlopePerRadian", liftSlopePerRadian)
        positive("maxLiftCoefficient", maxLiftCoefficient)
        nonNegative("zeroLiftDragCoefficient", zeroLiftDragCoefficient)
        nonNegative("inducedDragFactor", inducedDragFactor)
        nonNegative("stallDragCoefficient", stallDragCoefficient)
        positive("stallAoADegrees", stallAoADegrees)
        require(stallRecoveryAoADegrees >= 0.0 && stallRecoveryAoADegrees <= stallAoADegrees) {
            "stallRecoveryAoADegrees must be within the stall threshold"
        }
        positive("stallSpeedMps", stallSpeedMps)
        require(stallRecoverySpeedMps >= stallSpeedMps && stallRecoverySpeedMps.isFinite()) {
            "stallRecoverySpeedMps must be finite and not below stallSpeedMps"
        }
        require(maximumAoADegrees >= stallAoADegrees && maximumAoADegrees <= 89.0) {
            "maximumAoADegrees must bound the stall angle"
        }
        nonNegative("dryThrustNewtons", dryThrustNewtons)
        require(afterburnerMultiplier >= 1.0 && afterburnerMultiplier.isFinite()) {
            "afterburnerMultiplier must be finite and at least one"
        }
        if (afterburnerFuelSeconds != null) {
            require(afterburnerFuelSeconds >= 0.0 && afterburnerFuelSeconds.isFinite()) {
                "afterburnerFuelSeconds must be finite and nonnegative"
            }
        }
        nonNegative("afterburnerConsumptionPerSecond", afterburnerConsumptionPerSecond)
        positive("maxStructuralSpeedMps", maxStructuralSpeedMps)
        positive("maxEngineSpeedMps", maxEngineSpeedMps)
        positive("groundSpeedLimitMps", groundSpeedLimitMps)
        nonNegative("pitchAuthorityDegPerSecondSquared", pitchAuthorityDegPerSecondSquared)
        nonNegative("pitchDampingPerSecond", pitchDampingPerSecond)
        nonNegative("rollAuthorityDegPerSecondSquared", rollAuthorityDegPerSecondSquared)
        nonNegative("rollDampingPerSecond", rollDampingPerSecond)
        nonNegative("yawAuthorityDegPerSecondSquared", yawAuthorityDegPerSecondSquared)
        nonNegative("yawDampingPerSecond", yawDampingPerSecond)
        positive("controlEffectivenessDynamicPressurePa", controlEffectivenessDynamicPressurePa)
        fraction("lowSpeedControlFraction", lowSpeedControlFraction)
        fraction("stallControlFraction", stallControlFraction)
        nonNegative("bankTurnAuthorityDegPerSecondSquared", bankTurnAuthorityDegPerSecondSquared)
        nonNegative("yawSlipStabilityPerSecond", yawSlipStabilityPerSecond)
        nonNegative("groundFrictionPerSecond", groundFrictionPerSecond)
        nonNegative("groundBrakingPerSecond", groundBrakingPerSecond)
        nonNegative("throttleSpoolUpPerSecond", throttleSpoolUpPerSecond)
        nonNegative("throttleSpoolDownPerSecond", throttleSpoolDownPerSecond)
        nonNegative("wingDropRateDegPerSecondSquared", wingDropRateDegPerSecondSquared)
        require(stallRecoveryTicks > 0) { "stallRecoveryTicks must be positive" }
        positive("worldSpeedEnvelopeMps", worldSpeedEnvelopeMps)
        nonNegative("worldSpeedEnvelopeResponsePerSecond", worldSpeedEnvelopeResponsePerSecond)
        positive("joystickSensitivity", joystickSensitivity)
        fraction("joystickDeadzone", joystickDeadzone)
        nonNegative("joystickSmoothingPerSecond", joystickSmoothingPerSecond)
        nonNegative("joystickReturnPerSecond", joystickReturnPerSecond)
    }

    private fun positive(name: String, value: Double) {
        require(value > 0.0 && value.isFinite()) { "$name must be finite and positive" }
    }

    private fun nonNegative(name: String, value: Double) {
        require(value >= 0.0 && value.isFinite()) { "$name must be finite and nonnegative" }
    }

    private fun fraction(name: String, value: Double) {
        require(value in 0.0..1.0 && value.isFinite()) { "$name must be finite in [0,1]" }
    }
}
