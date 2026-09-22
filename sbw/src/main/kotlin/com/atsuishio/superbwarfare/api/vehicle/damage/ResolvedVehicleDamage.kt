package com.atsuishio.superbwarfare.api.vehicle.damage

import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContext
import net.minecraft.world.damagesource.DamageSource

/** Controls whether an already-resolved hit is also applied to SBW's legacy OBB part health. */
enum class ResolvedVehicleModulePolicy {
    APPLY_NATIVE,
    SKIP_NATIVE,
}

/**
 * Server-only damage commit. [amount] has already passed the caller's armor and
 * damage calculations and therefore is never run through SBW's DamageModifier again.
 */
data class ResolvedVehicleDamageRequest @JvmOverloads constructor(
    val source: DamageSource,
    val amount: Float,
    val modulePolicy: ResolvedVehicleModulePolicy = ResolvedVehicleModulePolicy.APPLY_NATIVE,
    val destructionContext: VehicleDestructionContext? = null,
    val feedback: Boolean = true,
    val lethal: Boolean = false,
)

enum class ResolvedVehicleDamageRejection {
    NONE,
    NOT_SERVER_AUTHORITY,
    SOURCE_REJECTED,
    INVALID_AMOUNT,
    ALREADY_DESTROYED,
}

data class ResolvedVehicleDamageResult(
    val accepted: Boolean,
    val appliedDamage: Float,
    val destroyed: Boolean,
    val rejection: ResolvedVehicleDamageRejection,
) {
    companion object {
        @JvmStatic
        fun rejected(reason: ResolvedVehicleDamageRejection) =
            ResolvedVehicleDamageResult(false, 0.0f, false, reason)
    }
}
