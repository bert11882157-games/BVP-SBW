package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult

/**
 * The admission/commit boundary for every vehicle shot route. Resolving a muzzle, touching ammo,
 * and emitting accepted-shot effects are deliberately downstream of the same admission check.
 * Inline callbacks keep idle/rapid-fire dispatch allocation-free and make the real ordering testable.
 */
internal object VehicleShotTransaction {
    inline fun <Context : Any> execute(
        weaponName: String?,
        wrecked: Boolean,
        serverAuthority: Boolean,
        allowsFire: () -> Boolean,
        resolve: () -> Context?,
        shoot: (Context) -> ShotResult,
        accepted: (Context) -> Unit,
    ): ShotResult {
        if (wrecked) return ShotResult.rejected(ShotRejectionReason.WRECKED, weaponName)
        if (!serverAuthority) return ShotResult.rejected(ShotRejectionReason.NOT_SERVER_AUTHORITY, weaponName)
        if (!allowsFire()) return ShotResult.rejected(ShotRejectionReason.ACTION_BLOCKED, weaponName)
        val context = resolve() ?: return ShotResult.rejected(ShotRejectionReason.NO_WEAPON, weaponName)
        val result = shoot(context)
        if (result.isAccepted()) accepted(context)
        return result
    }
}
