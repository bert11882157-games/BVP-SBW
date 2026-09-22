package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData

/** The two server-authoritative propulsion phases exposed to presentation providers. */
enum class GuidedPropulsionPhase(val code: Byte) {
    THRUST(0),
    FUEL_OUT(1);

    companion object {
        @JvmStatic
        fun fromCode(code: Byte): GuidedPropulsionPhase =
            if (code.toInt() == THRUST.code.toInt()) THRUST else FUEL_OUT
    }
}

/**
 * Immutable, bounded missile-relative propulsion parameters.  Speeds and acceleration are in
 * world blocks per tick, matching the projectile motion vectors used by SBW.  Inherited shooter
 * motion is intentionally not part of this value: the launch seam carries it separately and
 * adds it exactly once to the relative propulsion vector.
 */
data class GuidedPropulsionProfile(
    val initialSpeed: Double,
    val maxSpeed: Double,
    val accelerationPerTick: Double,
    val thrustDurationTicks: Int,
    val maxTurnRateDegreesPerSecond: Double,
    val guidanceLookAheadTicks: Int,
) {
    fun isValid(): Boolean =
        initialSpeed.isFinite() && initialSpeed > 0.0 && initialSpeed <= MAX_SPEED_BLOCKS_PER_TICK &&
            maxSpeed.isFinite() && maxSpeed >= initialSpeed && maxSpeed <= MAX_SPEED_BLOCKS_PER_TICK &&
            accelerationPerTick.isFinite() && accelerationPerTick > 0.0 &&
            accelerationPerTick <= MAX_SPEED_BLOCKS_PER_TICK &&
            thrustDurationTicks in 1..MAX_THRUST_DURATION_TICKS &&
            maxTurnRateDegreesPerSecond.isFinite() && maxTurnRateDegreesPerSecond > 0.0 &&
            maxTurnRateDegreesPerSecond <= MAX_TURN_RATE_DEGREES_PER_SECOND &&
            guidanceLookAheadTicks in 1..MAX_GUIDANCE_LOOK_AHEAD_TICKS

    /** Speed after [elapsedThrustTicks] completed acceleration steps, capped only relatively. */
    fun speedAfter(elapsedThrustTicks: Int): Double {
        val elapsed = elapsedThrustTicks.coerceAtLeast(0).toDouble()
        return (initialSpeed + accelerationPerTick * elapsed).coerceAtMost(maxSpeed)
    }

    companion object {
        /** Prevent malformed data from creating an unbounded per-tick displacement. */
        const val MAX_SPEED_BLOCKS_PER_TICK = 16.0
        /** A bounded authoring horizon; ordinary ATGM thrust values are far below this. */
        const val MAX_THRUST_DURATION_TICKS = 1200
        const val MAX_TURN_RATE_DEGREES_PER_SECOND = 180.0
        const val MAX_GUIDANCE_LOOK_AHEAD_TICKS = 60

        /** Explicit gameplay default for native wire-guided launchers without a datapack profile. */
        @JvmField
        val DEFAULT = GuidedPropulsionProfile(1.0, 4.0, 0.1, 30, 24.0, 12)

        @JvmStatic
        fun from(data: GuidedPropulsionData): GuidedPropulsionProfile? {
            val initial = data.initialSpeed ?: return null
            val maximum = data.maxSpeed ?: return null
            val acceleration = data.accelerationPerTick ?: return null
            val duration = data.thrustDurationTicks ?: return null
            val turnRate = data.maxTurnRateDegreesPerSecond ?: return null
            val lookAhead = data.guidanceLookAheadTicks ?: return null
            val profile = GuidedPropulsionProfile(initial, maximum, acceleration, duration, turnRate, lookAhead)
            return profile.takeIf { it.isValid() }
        }

        @JvmStatic
        fun from(
            initialSpeed: Double?,
            maxSpeed: Double?,
            accelerationPerTick: Double?,
            thrustDurationTicks: Int?,
            maxTurnRateDegreesPerSecond: Double?,
            guidanceLookAheadTicks: Int?,
        ): GuidedPropulsionProfile? = from(
            GuidedPropulsionData(initialSpeed, maxSpeed, accelerationPerTick, thrustDurationTicks,
                maxTurnRateDegreesPerSecond, guidanceLookAheadTicks)
        )
    }
}
