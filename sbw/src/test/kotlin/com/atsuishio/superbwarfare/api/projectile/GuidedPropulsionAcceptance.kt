package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.data.projectile.GuidedPropulsionData
import kotlinx.serialization.json.Json
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Standalone acceptance against the compiled production state, guidance, NBT and motion kernel. */
object GuidedPropulsionAcceptance {
    private var checks = 0
    private fun verify(value: Boolean) { checks++; check(value) { "assertion $checks" } }
    private fun near(actual: Double, expected: Double, tolerance: Double = 1e-8) {
        verify(abs(actual - expected) <= tolerance)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val maxima = listOf(3.0, 3.5, 3.8, 4.5, 5.0, 5.5, 6.0, 7.0, 7.5, 8.0, 9.0, 12.0)
        val directions = listOf(Vec3(0.0, 0.0, 1.0), Vec3(1.0, 0.4, 1.0).normalize(),
            Vec3(-1.0, -0.4, 1.0).normalize(), Vec3(0.0, 1.0, 0.0), Vec3(0.0, -1.0, 0.0))
        for (maximum in maxima) for (direction in directions) {
            val profile = GuidedPropulsionProfile(maximum * 0.35, maximum,
                maximum * 0.65 / 20.0, 20, 24.0, 12, ejectionSpeed = 2.0)
            var state = GuidedPropulsionState.launch(profile)
            var motion = direction.scale(profile.launchSpeed())
            var position = Vec3.ZERO
            repeat(6) { step ->
                verify(state.phase == GuidedPropulsionPhase.EJECTION)
                position = position.add(motion)
                motion = NominalProjectileMotion.afterFastThrowableAirStep(motion,
                    profile.ejectionGravityPerTick.toFloat().toDouble())
                verify(state.completeMovement(motion.length()) == null)
                verify(state.ejectionTicks == step + 1 && state.motorTicks == 0)
                val tag = CompoundTag(); state.writeTo(tag)
                val restored = GuidedPropulsionState.restore(tag, GuidedPropulsionProfile.DEFAULT)
                verify(restored != null && restored.profile == profile && restored.phase == state.phase)
                state = restored!!
            }
            if (direction == Vec3(0.0, 0.0, 1.0)) {
                near(position.z, profile.launchSpeed() * 6, 2e-6)
                near(position.y, -0.1875, 2e-7)
            }
            verify(state.phase == GuidedPropulsionPhase.THRUST)
            near(state.ignitionSpeed, motion.length())
            val inherited = Vec3(3.0, -0.5, 2.0)
            repeat(profile.thrustDurationTicks) { step ->
                val speed = requireNotNull(state.completeMovement(motion.length()))
                near(speed, profile.speedAfterIgnition(state.ignitionSpeed, step + 1))
                motion = motion.normalize().scale(speed)
                val steered = GuidedMissileGuidance.steer(motion.add(inherited), inherited,
                    Vec3(-1.0, 1.0, -1.0), profile.maxTurnRateDegreesPerSecond)
                near(steered.subtract(inherited).length(), speed)
                val tag = CompoundTag(); state.writeTo(tag)
                state = requireNotNull(GuidedPropulsionState.restore(tag, GuidedPropulsionProfile.DEFAULT))
            }
            verify(state.phase == GuidedPropulsionPhase.FUEL_OUT && state.motorTicks == profile.thrustDurationTicks)
            near(state.speed, maximum, 0.0)
            repeat(100) { verify(state.completeMovement(maximum * 0.7) == null) }
        }
        val legacyProfile = GuidedPropulsionProfile(1.0, 4.0, 0.1, 30, 24.0, 12)
        val old = CompoundTag().apply {
            putBoolean("GuidedPropulsionEnabled", true); putByte("GuidedPropulsionPhase", 0)
            putDouble("GuidedPropulsionSpeed", 2.0); putInt("GuidedPropulsionElapsed", 10)
        }
        var legacy = requireNotNull(GuidedPropulsionState.restore(old, legacyProfile))
        verify(legacy.burnMode == GuidedPropulsionState.BurnMode.LEGACY_AUTHORED)
        near(requireNotNull(legacy.completeMovement(2.0)), 2.1)
        val savedLegacy = CompoundTag(); legacy.writeTo(savedLegacy)
        legacy = requireNotNull(GuidedPropulsionState.restore(savedLegacy, GuidedPropulsionProfile.DEFAULT))
        near(requireNotNull(legacy.completeMovement(2.1)), 2.2)
        old.putByte("GuidedPropulsionPhase", 1)
        verify(GuidedPropulsionState.restore(old, GuidedPropulsionProfile.DEFAULT)!!.completeMovement(1.0) == null)
        val valid = CompoundTag(); GuidedPropulsionState.launch(GuidedPropulsionProfile.DEFAULT).writeTo(valid)
        val mutations: List<(CompoundTag) -> Unit> = listOf(
            { it.putInt("GuidedPropulsionVersion", 3) },
            { it.putByte("GuidedPropulsionPhase", 127) },
            { it.putInt("GuidedPropulsionEjectionElapsed", 6) },
            { it.putDouble("GuidedPropulsionSpeed", Double.NaN) },
            { it.putInt("GuidedPropulsionElapsed", 1) },
            { it.putString("GuidedPropulsionBurnMode", "UNKNOWN") },
            { it.remove("GuidedPropulsionFrozenProfile") },
            { it.getCompound("GuidedPropulsionFrozenProfile").putDouble("EjectionSpeed", -1.0) },
        )
        for (mutation in mutations) {
            val tag = valid.copy(); mutation(tag)
            verify(GuidedPropulsionState.restore(tag, GuidedPropulsionProfile.DEFAULT) == null)
        }
        val data = GuidedPropulsionData(1.0, 4.0, 0.1, 30, 24.0, 12)
        verify(GuidedPropulsionProfile.from(data) == legacyProfile)
        data.ignitionDelayTicks = 6
        verify(GuidedPropulsionProfile.from(data) == null)
        data.ejectionSpeed = 0.5; data.ejectionGravityPerTick = 0.0125
        val encoded = Json.encodeToString(GuidedPropulsionData.serializer(), data)
        verify(GuidedPropulsionProfile.from(Json.decodeFromString(GuidedPropulsionData.serializer(), encoded))
            == legacyProfile)
        val partial = GuidedPropulsionProfile.DEFAULT.toTag(); partial.remove("EjectionSpeed")
        verify(GuidedPropulsionProfile.fromTag(partial) == null)
        println("PASS $checks assertions: six gravity steps, exact burn boundary, inherited guidance, NBT replay/migration/rejection, DTO serialization")
    }
}
