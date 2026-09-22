package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/** Stable server-authoritative propulsion phase codes exposed to presentation providers. */
enum class GuidedPropulsionPhase(val code: Byte) {
    THRUST(0),
    FUEL_OUT(1),
    EJECTION(2);

    companion object {
        @JvmStatic
        fun fromCode(code: Byte): GuidedPropulsionPhase =
            entries.firstOrNull { it.code == code } ?: FUEL_OUT
    }
}

/**
 * Immutable, bounded missile-relative propulsion parameters.  Speeds and acceleration are in
 * world blocks per tick, matching the projectile motion vectors used by SBW.  Inherited shooter
 * motion is intentionally not part of this value: the launch seam carries it separately and
 * adds it exactly once to the relative propulsion vector.
 */
data class GuidedPropulsionProfile @JvmOverloads constructor(
    val initialSpeed: Double,
    val maxSpeed: Double,
    val accelerationPerTick: Double,
    val thrustDurationTicks: Int,
    val maxTurnRateDegreesPerSecond: Double,
    val guidanceLookAheadTicks: Int,
    val ignitionDelayTicks: Int = 6,
    val ejectionSpeed: Double = 0.5,
    val ejectionGravityPerTick: Double = 0.0125,
) {
    fun isValid(): Boolean =
        initialSpeed.isFinite() && initialSpeed > 0.0 && initialSpeed <= MAX_SPEED_BLOCKS_PER_TICK &&
            maxSpeed.isFinite() && maxSpeed >= initialSpeed && maxSpeed <= MAX_SPEED_BLOCKS_PER_TICK &&
            accelerationPerTick.isFinite() && accelerationPerTick > 0.0 &&
            accelerationPerTick <= MAX_SPEED_BLOCKS_PER_TICK &&
            thrustDurationTicks in 1..MAX_THRUST_DURATION_TICKS &&
            maxTurnRateDegreesPerSecond.isFinite() && maxTurnRateDegreesPerSecond > 0.0 &&
            maxTurnRateDegreesPerSecond <= MAX_TURN_RATE_DEGREES_PER_SECOND &&
            guidanceLookAheadTicks in 1..MAX_GUIDANCE_LOOK_AHEAD_TICKS &&
            ignitionDelayTicks in 1..60 &&
            ejectionSpeed.isFinite() && ejectionSpeed > 0.0 && ejectionSpeed <= MAX_SPEED_BLOCKS_PER_TICK &&
            ejectionGravityPerTick.isFinite() && ejectionGravityPerTick > 0.0 && ejectionGravityPerTick <= 0.1

    fun launchSpeed(): Double = minOf(ejectionSpeed, maxSpeed)

    /** A derived burn reaches the authored maximum after the authored number of motor updates. */
    fun speedAfterIgnition(ignitionSpeed: Double, elapsedThrustTicks: Int): Double {
        require(ignitionSpeed.isFinite() && ignitionSpeed > 0.0)
        if (elapsedThrustTicks >= thrustDurationTicks) return maxSpeed
        return ignitionSpeed + (maxSpeed - ignitionSpeed) *
            elapsedThrustTicks.coerceAtLeast(0).toDouble() / thrustDurationTicks
    }

    fun toTag(): CompoundTag = CompoundTag().apply {
        putDouble("InitialSpeed", initialSpeed)
        putDouble("MaxSpeed", maxSpeed)
        putDouble("AccelerationPerTick", accelerationPerTick)
        putInt("ThrustDurationTicks", thrustDurationTicks)
        putDouble("MaxTurnRateDegreesPerSecond", maxTurnRateDegreesPerSecond)
        putInt("GuidanceLookAheadTicks", guidanceLookAheadTicks)
        putInt("IgnitionDelayTicks", ignitionDelayTicks)
        putDouble("EjectionSpeed", ejectionSpeed)
        putDouble("EjectionGravityPerTick", ejectionGravityPerTick)
    }

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
        val DEFAULT = GuidedPropulsionProfile(2.0, 6.0, 4.0 / 20.0, 20, 24.0, 12,
            ejectionSpeed = 2.0)

        @JvmStatic
        fun from(data: GuidedPropulsionData): GuidedPropulsionProfile? {
            val initial = data.initialSpeed ?: return null
            val maximum = data.maxSpeed ?: return null
            val acceleration = data.accelerationPerTick ?: return null
            val duration = data.thrustDurationTicks ?: return null
            val turnRate = data.maxTurnRateDegreesPerSecond ?: return null
            val lookAhead = data.guidanceLookAheadTicks ?: return null
            val legacy = data.ignitionDelayTicks == null && data.ejectionSpeed == null &&
                data.ejectionGravityPerTick == null
            val profile = if (legacy) {
                GuidedPropulsionProfile(initial, maximum, acceleration, duration, turnRate, lookAhead)
            } else {
                GuidedPropulsionProfile(initial, maximum, acceleration, duration, turnRate, lookAhead,
                    data.ignitionDelayTicks ?: return null, data.ejectionSpeed ?: return null,
                    data.ejectionGravityPerTick ?: return null)
            }
            return profile.takeIf { it.isValid() }
        }

        @JvmStatic
        fun fromTag(tag: CompoundTag): GuidedPropulsionProfile? {
            fun double(key: String): Double? = if (tag.contains(key, Tag.TAG_DOUBLE.toInt()))
                tag.getDouble(key) else null
            fun integer(key: String): Int? = if (tag.contains(key, Tag.TAG_INT.toInt()))
                tag.getInt(key) else null
            val ejectionFields = listOf("IgnitionDelayTicks", "EjectionSpeed", "EjectionGravityPerTick")
            if (ejectionFields.any(tag::contains) &&
                (integer(ejectionFields[0]) == null || double(ejectionFields[1]) == null ||
                    double(ejectionFields[2]) == null)) return null
            return from(GuidedPropulsionData(double("InitialSpeed"), double("MaxSpeed"),
                double("AccelerationPerTick"), integer("ThrustDurationTicks"),
                double("MaxTurnRateDegreesPerSecond"), integer("GuidanceLookAheadTicks"),
                integer("IgnitionDelayTicks"), double("EjectionSpeed"), double("EjectionGravityPerTick")))
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
