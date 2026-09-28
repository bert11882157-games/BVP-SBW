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
    // Generic fallback jet (GAME_JET, tests): keeps the effective thrust it flew with before the 2026-09-28
    // acceleration nerf (6.0 x the old 1.792 game gain). Every real aircraft sets this from its reference thrust.
    val dryAccelerationMps2: Double = 10.752,
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
    /**
     * World m/s where this aircraft's own soft cap begins (its "willing" level speed); zero = only the shared
     * soft cap. Level flight passes it a little and slowly, dives go well past it. Not length-scaled.
     */
    val firstSoftSpeedLimitMps: Double = 0.0,
    /**
     * Multiplier on the engine's surplus over drag at low speed (owner, 2026-09-28: jets pick up speed briskly when
     * slow; only going fast is hard): this value up to LOW_SPEED_BOOST_FULL_KMH, back to 1 by LOW_SPEED_BOOST_END_KMH.
     * 1 = none. Top speeds are unchanged (the surplus is zero there).
     */
    val lowSpeedSurplusBoost: Double = 1.0,
) {
    /** Small shared gameplay tuning; reference specifications remain unchanged. */
    val gamePitchRateDegreesPerSecond: Double get() = pitchRateDegreesPerSecond * 1.10 * 1.12
    val gameRollRateDegreesPerSecond: Double get() = rollRateDegreesPerSecond * 0.90
    /** Reduce airbrake deceleration without weakening wheel brakes or changing reference data (owner: nerfed
     *  again 2026-09-28, 0.25 -> 0.12). */
    val gameAirbrakeDragPerMetre: Double get() = airbrakeDragPerMetre * 0.12
    /** Jets fly on their reference thrust (acceleration nerf, 2026-09-28: was x1.79); propellers keep a gain. */
    val gameDryAccelerationMps2: Double get() =
        dryAccelerationMps2 * (if (propellerPowerReferenceSpeedMps > 0.0) 1.6 else 1.0)

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
    /** Shared gameplay envelope. */
    val softSpeedLimitMps: Double get() = GLOBAL_SOFT_CAP_KMH / 3.6
    val hardSpeedLimitMps: Double get() = HARD_CAP_KMH / 3.6
    /** Where overspeed resistance begins: this aircraft's first soft cap, never above the shared one. */
    val effectiveSoftSpeedLimitMps: Double get() =
        if (firstSoftSpeedLimitMps > 0.0) minOf(firstSoftSpeedLimitMps, softSpeedLimitMps) else softSpeedLimitMps
    /** Multiplier on the engine's surplus over drag at this HUD speed (see [lowSpeedSurplusBoost]). */
    fun lowSpeedSurplusMultiplier(speedKmh: Double): Double {
        if (lowSpeedSurplusBoost <= 1.0 || !speedKmh.isFinite()) return 1.0
        val u = ((speedKmh - LOW_SPEED_BOOST_FULL_KMH) / (LOW_SPEED_BOOST_END_KMH - LOW_SPEED_BOOST_FULL_KMH))
            .coerceIn(0.0, 1.0)
        return lowSpeedSurplusBoost + (1.0 - lowSpeedSurplusBoost) * u
    }

    /** The engine's real afterburner ratio (the old x1.8 floor made light-up far too strong). */
    val gameAfterburnerMultiplier: Double get() = maxOf(1.0, afterburnerMultiplier)

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
        require(firstSoftSpeedLimitMps.isFinite() && firstSoftSpeedLimitMps >= 0.0)
        require(lowSpeedSurplusBoost.isFinite() && lowSpeedSurplusBoost in 1.0..4.0)
        require(operationalEnergyPerTick.toLong() * afterburnerEnergyMultiplier <= Int.MAX_VALUE)
    }

    companion object {
        /** HUD km/h of Mach 1 at sea level (owner's balance, 2026-09-28). */
        const val GAME_MACH_ONE_KMH = 400.0
        private const val SEA_LEVEL_SOUND_SPEED_MPS = 340.29398
        /**
         * Game sound speed over scaled real sound speed: the quarter-scale world would put Mach 1 at 306 km/h;
         * every Mach-dependent term (wave drag, jet ram thrust, HUD Mach, sonic boom) uses 400 km/h instead.
         */
        const val MACH_STRETCH = GAME_MACH_ONE_KMH / 3.6 / (SEA_LEVEL_SOUND_SPEED_MPS * 0.25)
        /** Shared soft cap (only the fastest jets reach it) and the hard clamp above it. */
        const val GLOBAL_SOFT_CAP_KMH = 650.0
        const val HARD_CAP_KMH = 750.0
        /** Soft-cap resistance per m/s and per (m/s)^2 of overspeed, and the share a vertical dive sheds. */
        const val OVERSPEED_LINEAR_PER_SECOND = 0.22
        const val OVERSPEED_QUADRATIC_PER_METRE = 0.005
        const val OVERSPEED_DIVE_RELIEF = 0.95
        /**
         * Transonic speed is earned (owner, 2026-09-28): from TRANSONIC_SURPLUS_START_MACH to
         * TRANSONIC_SURPLUS_FULL_MACH the engine's surplus over drag shrinks to TRANSONIC_SURPLUS of itself. Top speeds
         * are unchanged (the surplus is zero there either way) and gravity in a dive is not scaled.
         */
        const val TRANSONIC_SURPLUS_START_MACH = 0.85
        const val TRANSONIC_SURPLUS_FULL_MACH = 1.1
        const val TRANSONIC_SURPLUS = 0.22

        /** Low-speed surplus boost for jets (BVP flight references); see [lowSpeedSurplusBoost]. */
        const val JET_LOW_SPEED_BOOST = 1.8
        const val LOW_SPEED_BOOST_FULL_KMH = 150.0
        const val LOW_SPEED_BOOST_END_KMH = 300.0

        /** Share of the engine's surplus over drag that accelerates the aircraft at this flight Mach number. */
        @JvmStatic
        fun transonicSurplusShare(mach: Double): Double {
            if (!mach.isFinite() || mach <= TRANSONIC_SURPLUS_START_MACH) return 1.0
            val f = ((mach - TRANSONIC_SURPLUS_START_MACH) /
                (TRANSONIC_SURPLUS_FULL_MACH - TRANSONIC_SURPLUS_START_MACH)).coerceIn(0.0, 1.0)
            return 1.0 - (1.0 - TRANSONIC_SURPLUS) * f
        }

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
