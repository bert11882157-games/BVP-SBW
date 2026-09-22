package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation

enum class VehicleDamagedRecoveryMode {
    /** Preserve SBW 0.8.9 behavior: clear a damaged flag above the configured health fraction. */
    HEALTH_THRESHOLD,

    /** Only an explicit module-state write may clear a damaged flag. */
    EXPLICIT,
}

enum class VehicleCriticalPartFailureMode {
    /** Preserve SBW 0.8.9 vehicle-type checks. */
    LEGACY_VEHICLE_TYPE,

    /** At critical hull health, fail every present turret plus both engines. */
    TURRET_AND_ENGINES,

    NONE,
}

/** Native passive repair/part-recovery policy; addon actions remain a separate explicit system. */
data class VehicleRepairPolicy(
    val passiveHullRepairEnabled: Boolean = true,
    val passiveModuleRepairFractionPerTick: Float = 0.0025F,
    val damagedRecoveryMode: VehicleDamagedRecoveryMode = VehicleDamagedRecoveryMode.HEALTH_THRESHOLD,
    val damagedRecoveryThreshold: Float = 0.95F,
    val criticalPartFailureMode: VehicleCriticalPartFailureMode =
        VehicleCriticalPartFailureMode.LEGACY_VEHICLE_TYPE,
) {
    companion object {
        @JvmField
        val DEFAULT = VehicleRepairPolicy()
    }
}

fun interface VehicleRepairPolicyProvider { fun resolve(vehicle: VehicleEntity): VehicleRepairPolicy? }

object VehicleRepairPolicies {
    private val providers = OrderedProviderRegistry<ResourceLocation, VehicleRepairPolicyProvider>()

    @JvmStatic
    fun register(id: ResourceLocation, provider: VehicleRepairPolicyProvider) = providers.register(id, provider)

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = providers.unregister(id)

    @JvmStatic
    fun resolve(vehicle: VehicleEntity): VehicleRepairPolicy {
        for ((providerId, provider) in providers.snapshot()) {
            try {
                provider.resolve(vehicle)?.let { return it.sanitized() }
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Vehicle repair policy provider {} failed for {}", providerId, vehicle, exception)
            }
        }
        return VehicleRepairPolicy.DEFAULT
    }

    private fun VehicleRepairPolicy.sanitized() = if (
        passiveModuleRepairFractionPerTick.isFinite() && passiveModuleRepairFractionPerTick >= 0F &&
        damagedRecoveryThreshold.isFinite() && damagedRecoveryThreshold in 0F..1F
    ) this else copy(
        passiveModuleRepairFractionPerTick = passiveModuleRepairFractionPerTick
            .takeIf { it.isFinite() && it >= 0F }
            ?: 0F,
        damagedRecoveryThreshold = damagedRecoveryThreshold
            .takeIf(Float::isFinite)
            ?.coerceIn(0F, 1F)
            ?: 0.95F,
    )
}
