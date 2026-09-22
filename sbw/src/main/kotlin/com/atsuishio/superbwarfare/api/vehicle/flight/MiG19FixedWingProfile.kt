package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Baseline MiG-19S flight profile. Public performance values are separate from the strategy
 * so authored profiles can replace this object without changing the flight model or
 * the VehicleEntity integration.
 */
object MiG19FixedWingProfile {
    const val OFFICIAL_MAX_IAS_KMH = 1260.0
    const val OFFICIAL_MAX_SPEED_AT_ALTITUDE_KMH_RB_REFERENCE = 1451.0
    const val OFFICIAL_BASE_MASS_KG_RB_REFERENCE = 5550.0
    const val OFFICIAL_FULL_MAIN_FUEL_MASS_KG = 1800.0
    const val FULL_FUEL_REFERENCE_MASS_KG =
        OFFICIAL_BASE_MASS_KG_RB_REFERENCE + OFFICIAL_FULL_MAIN_FUEL_MASS_KG
    const val OFFICIAL_WING_LOADING_KG_PER_M2 = 292.0
    /** Derived from the published rounded loading at full main fuel. */
    const val REFERENCE_WING_AREA_M2 =
        FULL_FUEL_REFERENCE_MASS_KG / OFFICIAL_WING_LOADING_KG_PER_M2
    const val OFFICIAL_TAKEOFF_RUN_M = 515.0
    const val OFFICIAL_NR30_GUN_COUNT = 3
    const val OFFICIAL_DRY_THRUST_KGF_PER_ENGINE = 2300.0
    const val OFFICIAL_AFTERBURNER_THRUST_KGF_PER_ENGINE = 3250.0
    /**
     * Engineering calibration, not published RD-9B engine or drag tables.
     * The wave coefficient balances full-afterburner level flight at the sourced
     * RB Reference maximum, at 10 km ISA and the explicit full-fuel reference mass.
     */
    private const val ENGINEERING_WAVE_DRAG_COEFFICIENT = 0.0315259754587905
    private const val REFERENCE_WAVE_DRAG_PER_METRE =
        0.5 * 1.225 * REFERENCE_WING_AREA_M2 / FULL_FUEL_REFERENCE_MASS_KG *
            ENGINEERING_WAVE_DRAG_COEFFICIENT

    /**
     * The aerodynamic/control coefficients below are explicit tuning surfaces, not claims that
     * War Thunder publishes a complete MiG-19 coefficient table.  They are deliberately bounded
     * defaults pending calibration against a primary flight-model data set.
     */
    @JvmField
    val PROFILE = FixedWingFlightProfile(
        id = "mig-19s",
        massKg = FULL_FUEL_REFERENCE_MASS_KG,
        referenceWingAreaM2 = REFERENCE_WING_AREA_M2,
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

    /**
     * Full-scale control seed converted once to quarter-distance handling.
     * Actuator ceilings are engineering limits, not stat-card turn-time conversions.
     * Actual rates remain governed by airflow, AoA and attainable path curvature.
     */
    @JvmField
    val HANDLING = FixedWingReferenceHandling.fromReference(
        PROFILE,
        FixedWingHandlingProfile(
            gravityMps2 = 9.80665,
            liftReferenceSpeedMps = 62.0,
            trimSpeedMps = 120.0,
            minimumControlSpeedMps = 24.0,
            maximumSpeedMps = OFFICIAL_MAX_SPEED_AT_ALTITUDE_KMH_RB_REFERENCE / 3.6,
            maximumIndicatedSpeedMps = OFFICIAL_MAX_IAS_KMH / 3.6,
            recoverySpeedMps = 72.0,
            pitchRateDegreesPerSecond = 28.0,
            rollRateDegreesPerSecond = 120.0,
            rudderRateDegreesPerSecond = 18.0,
            maximumLoadFactor = 12.0,
            maximumNegativeLoadFactor = 6.0,
            pitchProtectionAngleDegrees = 13.5,
            airbrakeDragPerMetre = 0.0004,
            sideDragPerMetre = 0.0015,
            overspeedDragPerMetre = 0.0025,
            rollingResistanceMps2 = 0.25,
            groundBrakingMps2 = 6.0,
            taxiFullSteeringSpeedMps = 24.0,
            minimumStallStateSpeedMps = 4.0,
            spoolUpPerSecond = 0.8,
            spoolDownPerSecond = 1.2,
            jetThrustDensityExponent = 0.65,
            jetThrustDensityKneeRatio = FixedWingAtmosphere.densityRatio(10_000.0),
            jetDryMachFactor = 0.80,
            jetAfterburnerMachFactor = 1.45,
            waveDragOnsetMach = 0.82,
            waveDragWidthMach = 0.25,
            waveDragPerMetre = REFERENCE_WAVE_DRAG_PER_METRE,
        ),
        0.25,
    )
}
