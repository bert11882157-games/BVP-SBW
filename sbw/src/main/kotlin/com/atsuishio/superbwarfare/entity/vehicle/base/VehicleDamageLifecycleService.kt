package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDamagePolicy
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRejection
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageResult
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModTags
import com.atsuishio.superbwarfare.tools.DamageTypeTool
import com.atsuishio.superbwarfare.tools.OBB.Part.MAIN_ENGINE
import com.atsuishio.superbwarfare.tools.OBB.Part.SUB_ENGINE
import com.atsuishio.superbwarfare.tools.OBB.Part.TURRET
import com.atsuishio.superbwarfare.tools.OBB.Part.WHEEL_LEFT
import com.atsuishio.superbwarfare.tools.OBB.Part.WHEEL_RIGHT
import com.atsuishio.superbwarfare.tools.SeekTool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.projectile.Projectile
import kotlin.math.max
import kotlin.math.min

/** Owns damage admission and commit ordering behind VehicleEntity's compatibility facade. */
internal class VehicleDamageLifecycleService(
    private val vehicle: VehicleEntity,
) {
    fun acceptsSource(source: DamageSource): Boolean {
        if (source.`is`(ModTags.DamageTypes.VEHICLE_IMMUNE)) return false

        if (DamageTypeTool.isGunDamage(source) && source.entity != null && source.entity!!.vehicle === vehicle) {
            return false
        }

        val lastDriver = vehicle.lastDriver
        if (source.entity != null && lastDriver != null && SeekTool.IS_FRIENDLY.test(lastDriver, source.entity)
            && lastDriver.team != null && source.entity!!.team != null && source.entity!!.team === lastDriver.team
            && !source.entity!!.team!!.isAllowFriendlyFire
            && (source.entity === lastDriver && !source.`is`(ModDamageTypes.VEHICLE_STRIKE))
        ) {
            return false
        }

        return true
    }

    fun hurt(source: DamageSource, amount: Float): Boolean {
        if (!vehicle.acceptsDamageSource(source)) return false

        vehicle.reportDamageDebug(source, amount)

        val computedAmount = vehicle.computeVehicleDamageAfterModifiers(source, amount)
        commit(source, computedAmount, ResolvedVehicleModulePolicy.APPLY_NATIVE, true)
        return vehicle.invokeVanillaHurt(source, computedAmount)
    }

    fun applyResolved(request: ResolvedVehicleDamageRequest): ResolvedVehicleDamageResult {
        if (vehicle.level() !is ServerLevel) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.NOT_SERVER_AUTHORITY)
        }
        if (!request.amount.isFinite() || request.amount <= 0f) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.INVALID_AMOUNT)
        }
        if (vehicle.isWreck || vehicle.health <= 0f || !vehicle.isAlive) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.ALREADY_DESTROYED)
        }
        if (!vehicle.acceptsDamageSource(request.source)) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.SOURCE_REJECTED)
        }

        vehicle.reportDamageDebug(request.source, request.amount)

        val requestedCommitAmount = if (request.lethal) max(request.amount, vehicle.health) else request.amount
        val commitAmount = min(requestedCommitAmount, vehicle.getMaxHealth() + 1f)
        val healthBefore = vehicle.health
        commit(request.source, commitAmount, request.modulePolicy, request.feedback)
        val appliedAmount = (healthBefore - vehicle.health).coerceAtLeast(0f)

        var destroyed = false
        if (vehicle.health <= 0f && !vehicle.isWreck) {
            vehicle.destroy(request.destructionContext ?: vehicle.defaultDestructionContext())
            destroyed = true
        }

        return ResolvedVehicleDamageResult(
            accepted = true,
            appliedDamage = appliedAmount,
            destroyed = destroyed,
            rejection = ResolvedVehicleDamageRejection.NONE,
        )
    }

    fun computeAfterModifiers(source: DamageSource, amount: Float): Float =
        if (source.`is`(ModTags.DamageTypes.BYPASSES_VEHICLE)) amount
        else vehicle.getDamageModifier().compute(source, amount)

    private fun commit(
        source: DamageSource,
        amount: Float,
        modulePolicy: ResolvedVehicleModulePolicy,
        feedback: Boolean,
    ) {
        vehicle.crash = source.`is`(ModDamageTypes.VEHICLE_STRIKE)

        if (source.entity != null) {
            vehicle.lastAttackerUUID = source.entity!!.stringUUID
        }

        val projectile = source.directEntity
        val suppressNativeModuleDamage = projectile is ProjectileImpactDamagePolicy &&
            projectile.suppressesNativeVehicleModuleDamage()
        if (modulePolicy == ResolvedVehicleModulePolicy.APPLY_NATIVE &&
            !suppressNativeModuleDamage && projectile is Projectile
        ) {
            val accessor = OBBHitter.getInstance(projectile)
            val part = accessor.`sbw$getCurrentHitPart`()

            if (part != null) {
                when (part) {
                    TURRET -> vehicle.turretHealth -= amount
                    WHEEL_LEFT -> vehicle.leftWheelHealth -= amount
                    WHEEL_RIGHT -> vehicle.rightWheelHealth -= amount
                    MAIN_ENGINE -> vehicle.mainEngineHealth -= amount
                    SUB_ENGINE -> vehicle.subEngineHealth -= amount
                    else -> Unit
                }
            }
        }

        vehicle.lastDamageSource = source
        vehicle.lastDamageStamp = vehicle.level().gameTime
        val healthBefore = vehicle.health
        vehicle.onHurt(amount, source.entity, feedback)
        if (EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "damage", "hull_commit",
                "source", source.msgId, "owner", source.entity?.uuid,
                "direct_entity", source.directEntity?.uuid, "requested_damage", amount,
                "health_before", healthBefore, "health_after", vehicle.health,
                "module_policy", modulePolicy, "native_modules_suppressed", suppressNativeModuleDamage)
        }
    }
}
