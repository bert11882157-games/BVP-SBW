package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRejection
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageResult
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import kotlin.math.max
import kotlin.math.min

/**
 * Owns damage admission and commit ordering without access to an entity, world, or renderer.
 * Already-resolved damage bypasses modifiers; legacy hurt preserves its separate vanilla hook.
 * Rejection precedes mutation. Exceptions propagate; committed world effects are not rolled back.
 */
internal class VehicleDamageTransaction<Source : Any, Destruction : Any>(
    private val access: VehicleDamageAccess<Source, Destruction>,
) {
    fun hurt(source: Source, amount: Float): Boolean {
        if (!access.acceptsSource(source)) return false
        access.reportDebug(source, amount)
        val computedAmount = access.computeAfterModifiers(source, amount)
        access.commit(source, computedAmount, ResolvedVehicleModulePolicy.APPLY_NATIVE, true)
        return access.invokeVanillaHurt(source, computedAmount)
    }

    fun applyResolved(
        source: Source,
        amount: Float,
        lethal: Boolean,
        modulePolicy: ResolvedVehicleModulePolicy,
        feedback: Boolean,
        destructionContext: Destruction?,
    ): ResolvedVehicleDamageResult {
        if (!access.isServerAuthority) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.NOT_SERVER_AUTHORITY)
        }
        if (!amount.isFinite() || amount <= 0f) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.INVALID_AMOUNT)
        }
        if (access.isWreck || access.health <= 0f || !access.isAlive) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.ALREADY_DESTROYED)
        }
        if (!access.acceptsSource(source)) {
            return ResolvedVehicleDamageResult.rejected(ResolvedVehicleDamageRejection.SOURCE_REJECTED)
        }

        access.reportDebug(source, amount)
        val requestedCommitAmount = if (lethal) max(amount, access.health) else amount
        val commitAmount = min(requestedCommitAmount, access.maxHealth + 1f)
        val healthBefore = access.health
        access.commit(source, commitAmount, modulePolicy, feedback)
        val appliedAmount = (healthBefore - access.health).coerceAtLeast(0f)

        var destroyed = false
        if (access.health <= 0f && !access.isWreck) {
            access.destroy(destructionContext ?: access.defaultDestructionContext())
            destroyed = true
        }

        return ResolvedVehicleDamageResult(
            accepted = true,
            appliedDamage = appliedAmount,
            destroyed = destroyed,
            rejection = ResolvedVehicleDamageRejection.NONE,
        )
    }
}
