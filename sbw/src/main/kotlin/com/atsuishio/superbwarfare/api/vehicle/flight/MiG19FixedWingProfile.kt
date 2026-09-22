package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Baseline contract for a future MiG-19S BVP entity.  The public performance values are kept
 * separate from the strategy so a later authoring row can replace this object without changing
 * the flight model or the VehicleEntity integration.
 */
object MiG19FixedWingProfile {
    const val OFFICIAL_MAX_IAS_KMH = 1260.0
    const val OFFICIAL_MAX_SPEED_AT_ALTITUDE_KMH_RB_REFERENCE = 1435.0
    const val OFFICIAL_BASE_MASS_KG_RB_REFERENCE = 5730.0
    const val OFFICIAL_WING_LOADING_KG_PER_M2 = 292.0
    const val OFFICIAL_REFERENCE_AREA_M2 = 19.6232877
    const val OFFICIAL_TAKEOFF_RUN_M = 515.0
    const val OFFICIAL_NR30_GUN_COUNT = 3
    const val OFFICIAL_DRY_THRUST_KGF_PER_ENGINE = 2600.0
    const val OFFICIAL_AFTERBURNER_THRUST_KGF_PER_ENGINE = 3250.0

    /**
     * The aerodynamic/control coefficients below are explicit tuning surfaces, not claims that
     * War Thunder publishes a complete MiG-19 coefficient table.  They are deliberately bounded
     * defaults until a primary flight-model data set is selected by the BVP authoring lane.
     */
    @JvmField
    val PROFILE = FixedWingFlightProfile(
        id = "mig-19s",
        massKg = OFFICIAL_BASE_MASS_KG_RB_REFERENCE,
        referenceWingAreaM2 = OFFICIAL_REFERENCE_AREA_M2,
        liftSlopePerRadian = 5.4,
        maxLiftCoefficient = 1.25,
        zeroLiftDragCoefficient = 0.022,
        inducedDragFactor = 0.045,
        stallDragCoefficient = 0.65,
        stallAoADegrees = 15.0,
        stallRecoveryAoADegrees = 10.0,
        stallSpeedMps = 58.0,
        stallRecoverySpeedMps = 72.0,
        maximumAoADegrees = 28.0,
        dryThrustNewtons = 2.0 * OFFICIAL_DRY_THRUST_KGF_PER_ENGINE * 9.80665,
        afterburnerEnabled = true,
        afterburnerMultiplier = OFFICIAL_AFTERBURNER_THRUST_KGF_PER_ENGINE / OFFICIAL_DRY_THRUST_KGF_PER_ENGINE,
        afterburnerFuelSeconds = null,
        afterburnerConsumptionPerSecond = 0.0,
        maxStructuralSpeedMps = OFFICIAL_MAX_IAS_KMH / 3.6,
        maxEngineSpeedMps = OFFICIAL_MAX_SPEED_AT_ALTITUDE_KMH_RB_REFERENCE / 3.6,
        groundSpeedLimitMps = 90.0,
        pitchAuthorityDegPerSecondSquared = 160.0,
        pitchDampingPerSecond = 4.5,
        rollAuthorityDegPerSecondSquared = 400.0,
        rollDampingPerSecond = 5.0,
        yawAuthorityDegPerSecondSquared = 150.0,
        yawDampingPerSecond = 3.0,
        controlEffectivenessDynamicPressurePa = 0.5 * 1.225 * 120.0 * 120.0,
        lowSpeedControlFraction = 0.15,
        stallControlFraction = 0.20,
        bankTurnAuthorityDegPerSecondSquared = 65.0,
        yawSlipStabilityPerSecond = 1.2,
        groundFrictionPerSecond = 5.5,
        groundBrakingPerSecond = 8.0,
        throttleSpoolUpPerSecond = 0.8,
        throttleSpoolDownPerSecond = 1.2,
        wingDropRateDegPerSecondSquared = 28.0,
        stallRecoveryTicks = 8,
        worldSpeedEnvelopeMps = 90.0,
        worldSpeedEnvelopeResponsePerSecond = 2.5,
        joystickSensitivity = 0.02,
        joystickDeadzone = 0.03,
        joystickSmoothingPerSecond = 12.0,
        joystickReturnPerSecond = 6.0,
    )
}
