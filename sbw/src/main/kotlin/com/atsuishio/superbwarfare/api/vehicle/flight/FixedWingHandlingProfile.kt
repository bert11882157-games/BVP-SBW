package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Aircraft handling in game-world metres and seconds. Accelerations are normalized per unit
 * mass; these tuning values are not a claim of full-scale aircraft performance.
 */
data class FixedWingHandlingProfile(
    val gravityMps2: Double = 9.80665,
    val liftReferenceSpeedMps: Double = 14.0,
    val trimSpeedMps: Double = 24.0,
    val minimumControlSpeedMps: Double = 6.0,
    val maximumSpeedMps: Double = 36.0,
    val stallAngleDegrees: Double = 16.0,
    val recoveryAngleDegrees: Double = 10.0,
    val recoverySpeedMps: Double = 16.0,
    val recoveryTicks: Int = 8,
    val dryAccelerationMps2: Double = 6.0,
    val afterburnerMultiplier: Double = 1.6,
    val spoolUpPerSecond: Double = 0.5,
    val spoolDownPerSecond: Double = 0.75,
    val parasiteDragPerMetre: Double = 0.003,
    val inducedDragMps2: Double = 0.9,
    val airbrakeDragPerMetre: Double = 0.01,
    val rollingResistanceMps2: Double = 0.25,
    val groundBrakingMps2: Double = 6.0,
    val groundLateralResponsePerSecond: Double = 6.0,
    val pitchRateDegreesPerSecond: Double = 40.0,
    val rollRateDegreesPerSecond: Double = 120.0,
    val rudderRateDegreesPerSecond: Double = 18.0,
    val angularResponsePerSecond: Double = 8.0,
    val pitchStabilityPerSecond: Double = 1.2,
    val headingStabilityPerSecond: Double = 1.5,
    val wingDropDegreesPerSecond: Double = 20.0,
    val overspeedResponsePerSecond: Double = 3.0,
    val stickSensitivity: Double = 0.01,
    val stickDeadzone: Double = 0.04,
    val stickResponsePerSecond: Double = 16.0,
    val recenterResponsePerSecond: Double = 24.0,
    /** Availability/debit units of the shared operational-power policy, not a fuel item. */
    val operationalEnergyPerTick: Int = 1,
    val afterburnerEnergyMultiplier: Int = 2,
    val automaticReturnPerSecond: Double = 1.25,
    val overspeedDragPerMetre: Double = 0.25,
    val sideDragPerMetre: Double = 0.006,
    val maximumLoadFactor: Double = 7.5,
    /** Linear share of the odd pitch curve; the remaining share is quadratic. */
    val pitchResponseLinearFraction: Double = 0.4,
    val pitchProtectionAngleDegrees: Double = 13.5,
    val pitchAoAResponsePerSecond: Double = 3.0,
    /** Reference-to-world length scale; seconds and angles are unchanged. */
    val simulationLengthScale: Double = 1.0,
    val maximumIndicatedSpeedMps: Double = maximumSpeedMps,
    val maximumNegativeLoadFactor: Double = maximumLoadFactor,
    val normalizedLiftSlopePerDegree: Double = 1.0 / stallAngleDegrees,
    val stallDragPerMetre: Double = 0.012,
    val taxiFullSteeringSpeedMps: Double = 6.0,
    val minimumStallStateSpeedMps: Double = 1.0,
    /** Zero for jets; otherwise static-thrust to constant-power transition speed. */
    val propellerPowerReferenceSpeedMps: Double = 0.0,
    /** Opt-in engineering jet envelope; zero/one defaults preserve existing profiles. */
    val jetThrustDensityExponent: Double = 0.0,
    /** Zero disables the high-altitude, linear-density mass-flow regime. */
    val jetThrustDensityKneeRatio: Double = 0.0,
    val jetDryMachFactor: Double = 1.0,
    val jetAfterburnerMachFactor: Double = 1.0,
    val waveDragOnsetMach: Double = 0.82,
    val waveDragWidthMach: Double = 0.25,
    val waveDragPerMetre: Double = 0.0,
    /** Optional game-speed calibration; reference engine and aerodynamic data remain unchanged. */
    val takeoffHandling: FixedWingTakeoffHandling? = null,
) {
    /** Small shared gameplay tuning; reference specifications remain unchanged. */
    val gamePitchRateDegreesPerSecond: Double get() = pitchRateDegreesPerSecond * 1.10 * 1.12
    val gameRollRateDegreesPerSecond: Double get() = rollRateDegreesPerSecond * 0.90
    /** Reduce airbrake deceleration without weakening wheel brakes or changing reference data. */
    val gameAirbrakeDragPerMetre: Double get() = airbrakeDragPerMetre * 0.25
    val gameDryAccelerationMps2: Double get() = dryAccelerationMps2 * 1.12 * 1.6

    /** Modest airborne turn assistance above one G; trim and low-speed lift stay unchanged. */
    fun gameTurnLoadFactor(load: Double): Double {
        val magnitude = kotlin.math.abs(load)
        return if (magnitude <= 1.0) load
            else kotlin.math.sign(load) * (1.0 + (magnitude - 1.0) * 1.12)
    }

    /** Extra gameplay energy cost follows actual rolling and ascent, not bank or nose angle. */
    fun maneuverDragMps2(speed: Double, verticalSpeed: Double, rollRate: Double, grounded: Boolean): Double {
        if (grounded || speed <= 0.0) return 0.0
        val rollFraction = (kotlin.math.abs(rollRate) / maxOf(1.0, gameRollRateDegreesPerSecond)).coerceIn(0.0, 1.0)
        val climbFraction = (verticalSpeed / speed).coerceIn(0.0, 1.0)
        return 0.0004 * speed * speed * rollFraction * rollFraction + 0.4 * gravityMps2 * climbFraction
    }

    /** Strongly soften 0-50 km/h, then smoothly recover full thrust by 70 km/h. */
    fun launchThrustMultiplier(speedMps: Double): Double {
        return 0.35 + 0.65 * launchBlend(speedMps)
    }
    fun launchGroundAssistMultiplier(speedMps: Double): Double = 0.15 + 0.85 * launchBlend(speedMps)
    private fun launchBlend(speedMps: Double): Double =
        ((speedMps * 3.6 - 50.0) / 20.0).coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
    /** Shared gameplay envelope; reference airframe speeds do not impose a second lower cap. */
    val softSpeedLimitMps: Double get() = 400.0 / 3.6
    val hardSpeedLimitMps: Double get() = 500.0 / 3.6
    /** Gameplay boost is separate from the retained full-scale engine reference. */
    val gameAfterburnerMultiplier: Double get() = maxOf(1.8, afterburnerMultiplier)

    /** Wheel steering peaks at walking taxi speed, then tapers for runway stability. */
    fun taxiTurnRateDegreesPerSecond(speedMps: Double): Double {
        val speed = kotlin.math.abs(speedMps)
        val ramp = (speed / (5.0 / 3.6)).coerceIn(0.0, 1.0)
        val highSpeedTaper = 1.0 / (1.0 + maxOf(0.0, speed - 8.0 / 3.6) / 2.0)
        return 45.0 * ramp * highSpeedTaper
    }

    /** Normalized surface travel per second, independent of airspeed and aerodynamic authority. */
    val elevatorTravelPerSecond: Double get() = (0.5 * angularResponsePerSecond).coerceIn(1.0, 4.0)
    val aileronTravelPerSecond: Double get() = angularResponsePerSecond.coerceIn(1.5, 10.0)
    // The rudder moves 30% faster than the elevator: a more sensitive, quicker yaw response.
    val rudderTravelPerSecond: Double get() = (elevatorTravelPerSecond * 1.3).coerceAtMost(5.2)

    /** Passive windmilling/inlet resistance relative to zero-lift drag at power off. */
    val idleDragFactor: Double get() = if (propellerPowerReferenceSpeedMps > 0.0) 3.0 else 1.0

    val afterburnerOperationalEnergyPerTick: Int
        get() = operationalEnergyPerTick * afterburnerEnergyMultiplier
    val trimAngleDegrees: Double
        get() = (liftReferenceSpeedMps / trimSpeedMps) *
            (liftReferenceSpeedMps / trimSpeedMps) / normalizedLiftSlopePerDegree

    /** Pitch alone may use the lower rotation reference; roll and yaw keep their flight envelope. */
    fun pitchAirflowAuthority(speedSquared: Double, densityRatio: Double): Double {
        val reference = takeoffHandling?.referenceSpeedMps ?: trimSpeedMps
        val minimumSquared = minimumControlSpeedMps * minimumControlSpeedMps
        val pressure = ((densityRatio * speedSquared - minimumSquared) /
            (reference * reference - minimumSquared)).coerceIn(0.0, 1.0)
        return pressure * pressure * (3.0 - 2.0 * pressure)
    }

    /** Continuous across liftoff and exactly unity at/above the existing control-reference TAS. */
    fun lowSpeedThrustMultiplier(trueSpeedMps: Double): Double {
        val calibration = takeoffHandling ?: return 1.0
        val progress = ((trueSpeedMps - calibration.referenceSpeedMps) /
            (trimSpeedMps - calibration.referenceSpeedMps)).coerceIn(0.0, 1.0)
        val taper = progress * progress * (3.0 - 2.0 * progress)
        return 1.0 + (calibration.lowSpeedThrustMultiplier - 1.0) * (1.0 - taper)
    }

    init {
        require(simulationLengthScale.isFinite() && simulationLengthScale > 0.0)
        require(maximumIndicatedSpeedMps.isFinite() && maximumIndicatedSpeedMps > 0.0)
        require(maximumNegativeLoadFactor.isFinite() && maximumNegativeLoadFactor > 0.0)
        require(normalizedLiftSlopePerDegree.isFinite() && normalizedLiftSlopePerDegree > 0.0)
        require(stallDragPerMetre.isFinite() && stallDragPerMetre >= 0.0)
        require(taxiFullSteeringSpeedMps.isFinite() && taxiFullSteeringSpeedMps > 0.0)
        require(minimumStallStateSpeedMps.isFinite() && minimumStallStateSpeedMps > 0.0)
        require(propellerPowerReferenceSpeedMps.isFinite() && propellerPowerReferenceSpeedMps >= 0.0)
        require(jetThrustDensityExponent.isFinite() && jetThrustDensityExponent in 0.0..2.0)
        require(jetThrustDensityKneeRatio.isFinite() && jetThrustDensityKneeRatio in 0.0..1.5)
        require(jetDryMachFactor.isFinite() && jetDryMachFactor > 0.0)
        require(jetAfterburnerMachFactor.isFinite() && jetAfterburnerMachFactor > 0.0)
        require(waveDragOnsetMach.isFinite() && waveDragOnsetMach >= 0.0)
        require(waveDragWidthMach.isFinite() && waveDragWidthMach > 0.0)
        require(waveDragPerMetre.isFinite() && waveDragPerMetre >= 0.0)
        val positive = doubleArrayOf(
            gravityMps2, liftReferenceSpeedMps, trimSpeedMps, minimumControlSpeedMps, maximumSpeedMps,
            stallAngleDegrees, recoverySpeedMps, angularResponsePerSecond,
            stickSensitivity, stickResponsePerSecond,
            recenterResponsePerSecond, maximumLoadFactor, pitchProtectionAngleDegrees,
            pitchAoAResponsePerSecond,
        )
        require(positive.all { it.isFinite() && it > 0.0 })
        val nonnegative = doubleArrayOf(
            recoveryAngleDegrees, dryAccelerationMps2, spoolUpPerSecond, spoolDownPerSecond,
            parasiteDragPerMetre, inducedDragMps2, airbrakeDragPerMetre,
            rollingResistanceMps2, groundBrakingMps2, groundLateralResponsePerSecond,
            pitchRateDegreesPerSecond, rollRateDegreesPerSecond, rudderRateDegreesPerSecond,
            pitchStabilityPerSecond, headingStabilityPerSecond, wingDropDegreesPerSecond,
            automaticReturnPerSecond, overspeedResponsePerSecond, overspeedDragPerMetre, sideDragPerMetre,
        )
        require(nonnegative.all { it.isFinite() && it >= 0.0 })
        require(liftReferenceSpeedMps < trimSpeedMps && trimSpeedMps < maximumSpeedMps)
        require(minimumControlSpeedMps < liftReferenceSpeedMps)
        require(takeoffHandling == null || (takeoffHandling.referenceSpeedMps > minimumControlSpeedMps &&
            takeoffHandling.referenceSpeedMps < trimSpeedMps))
        require(recoverySpeedMps >= liftReferenceSpeedMps && recoverySpeedMps < maximumSpeedMps)
        require(recoveryAngleDegrees < stallAngleDegrees && stallAngleDegrees < 45.0)
        require(pitchProtectionAngleDegrees < stallAngleDegrees)
        require(pitchResponseLinearFraction.isFinite() && pitchResponseLinearFraction in 0.0..1.0)
        require(recoveryTicks > 0)
        require(afterburnerMultiplier.isFinite() && afterburnerMultiplier >= 1.0)
        require(stickDeadzone.isFinite() && stickDeadzone in 0.0..0.25)
        require(operationalEnergyPerTick >= 0)
        require(afterburnerEnergyMultiplier >= 1)
        require(operationalEnergyPerTick.toLong() * afterburnerEnergyMultiplier <= Int.MAX_VALUE)
    }

    companion object {
        @JvmField
        val GAME_JET = FixedWingHandlingProfile()
    }
}

/** Explicit gameplay adjustment to low-speed thrust and pitch response, expressed in world units. */
data class FixedWingTakeoffHandling(val referenceSpeedMps: Double, val lowSpeedThrustMultiplier: Double) {
    init {
        require(referenceSpeedMps.isFinite() && referenceSpeedMps > 0.0)
        require(lowSpeedThrustMultiplier.isFinite() && lowSpeedThrustMultiplier in 1.0..4.0)
    }
}
