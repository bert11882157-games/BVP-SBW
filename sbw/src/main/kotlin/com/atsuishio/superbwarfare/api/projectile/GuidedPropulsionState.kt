package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/** Owns propulsion transitions; movement and guidance remain the projectile's responsibility. */
class GuidedPropulsionState private constructor(
    val profile: GuidedPropulsionProfile,
    var phase: GuidedPropulsionPhase,
    var speed: Double,
    var ejectionTicks: Int,
    var motorTicks: Int,
    var ignitionSpeed: Double,
    val burnMode: BurnMode,
) {
    enum class BurnMode { DERIVED, LEGACY_AUTHORED }

    /** Returns a new motor magnitude only for a powered update, including the final one. */
    fun completeMovement(relativeSpeed: Double): Double? {
        require(relativeSpeed.isFinite() && relativeSpeed >= 0.0)
        return when (phase) {
            GuidedPropulsionPhase.EJECTION -> {
                speed = relativeSpeed
                ejectionTicks++
                if (ejectionTicks >= profile.ignitionDelayTicks) {
                    require(relativeSpeed > 0.0)
                    ignitionSpeed = relativeSpeed
                    phase = GuidedPropulsionPhase.THRUST
                }
                null
            }
            GuidedPropulsionPhase.THRUST -> {
                if (motorTicks >= profile.thrustDurationTicks) {
                    phase = GuidedPropulsionPhase.FUEL_OUT
                    null
                } else {
                    motorTicks++
                    speed = if (burnMode == BurnMode.LEGACY_AUTHORED) profile.speedAfter(motorTicks)
                        else profile.speedAfterIgnition(ignitionSpeed, motorTicks)
                    if (motorTicks == profile.thrustDurationTicks) phase = GuidedPropulsionPhase.FUEL_OUT
                    speed
                }
            }
            GuidedPropulsionPhase.FUEL_OUT -> null
        }
    }

    fun writeTo(tag: CompoundTag) {
        tag.putInt("GuidedPropulsionVersion", 2)
        tag.putBoolean("GuidedPropulsionEnabled", true)
        tag.putByte("GuidedPropulsionPhase", phase.code)
        tag.putDouble("GuidedPropulsionSpeed", speed)
        tag.putInt("GuidedPropulsionElapsed", motorTicks)
        tag.putInt("GuidedPropulsionEjectionElapsed", ejectionTicks)
        tag.putDouble("GuidedPropulsionIgnitionSpeed", ignitionSpeed)
        tag.putString("GuidedPropulsionBurnMode", burnMode.name)
        tag.put("GuidedPropulsionFrozenProfile", profile.toTag())
    }

    companion object {
        @JvmStatic
        fun launch(profile: GuidedPropulsionProfile): GuidedPropulsionState {
            require(profile.isValid())
            return GuidedPropulsionState(profile, GuidedPropulsionPhase.EJECTION,
                profile.launchSpeed(), 0, 0, 0.0, BurnMode.DERIVED)
        }

        /** Invalid saves return null; the entity must not reinterpret them as a fresh launch. */
        @JvmStatic
        fun restore(tag: CompoundTag, legacyProfile: GuidedPropulsionProfile): GuidedPropulsionState? {
            if (!tag.getBoolean("GuidedPropulsionEnabled") ||
                !tag.contains("GuidedPropulsionPhase", Tag.TAG_BYTE.toInt()) ||
                !tag.contains("GuidedPropulsionSpeed", Tag.TAG_DOUBLE.toInt()) ||
                !tag.contains("GuidedPropulsionElapsed", Tag.TAG_INT.toInt())) return null
            val code = tag.getByte("GuidedPropulsionPhase")
            val phase = GuidedPropulsionPhase.entries.firstOrNull { it.code == code } ?: return null
            val speed = tag.getDouble("GuidedPropulsionSpeed")
            val motor = tag.getInt("GuidedPropulsionElapsed")
            val versioned = tag.contains("GuidedPropulsionVersion")
            if (versioned && (!tag.contains("GuidedPropulsionVersion", Tag.TAG_INT.toInt()) ||
                    tag.getInt("GuidedPropulsionVersion") != 2)) return null
            val profile = if (versioned) {
                if (!tag.contains("GuidedPropulsionFrozenProfile", Tag.TAG_COMPOUND.toInt())) return null
                GuidedPropulsionProfile.fromTag(tag.getCompound("GuidedPropulsionFrozenProfile"))
                    ?: return null
            } else legacyProfile
            if (!profile.isValid() || !speed.isFinite() || speed < 0.0 ||
                speed > GuidedPropulsionProfile.MAX_SPEED_BLOCKS_PER_TICK ||
                motor !in 0..profile.thrustDurationTicks) return null
            if (!versioned) {
                if (phase == GuidedPropulsionPhase.EJECTION || speed < profile.initialSpeed ||
                    speed > profile.maxSpeed) return null
                return GuidedPropulsionState(profile, phase, speed, profile.ignitionDelayTicks,
                    motor, profile.initialSpeed, BurnMode.LEGACY_AUTHORED)
            }
            if (!tag.contains("GuidedPropulsionEjectionElapsed", Tag.TAG_INT.toInt()) ||
                !tag.contains("GuidedPropulsionIgnitionSpeed", Tag.TAG_DOUBLE.toInt()) ||
                !tag.contains("GuidedPropulsionBurnMode", Tag.TAG_STRING.toInt())) return null
            val ejection = tag.getInt("GuidedPropulsionEjectionElapsed")
            val ignition = tag.getDouble("GuidedPropulsionIgnitionSpeed")
            val mode = BurnMode.entries.firstOrNull { it.name == tag.getString("GuidedPropulsionBurnMode") }
                ?: return null
            if (ejection !in 0..profile.ignitionDelayTicks || !ignition.isFinite() ||
                ignition < 0.0 || ignition > GuidedPropulsionProfile.MAX_SPEED_BLOCKS_PER_TICK) return null
            if (phase == GuidedPropulsionPhase.EJECTION) {
                if (ejection >= profile.ignitionDelayTicks || motor != 0 || ignition != 0.0 ||
                    mode != BurnMode.DERIVED) return null
            } else if (ejection != profile.ignitionDelayTicks || ignition <= 0.0) return null
            if (phase == GuidedPropulsionPhase.THRUST && motor >= profile.thrustDurationTicks) return null
            return GuidedPropulsionState(profile, phase, speed, ejection, motor, ignition, mode)
        }
    }
}
