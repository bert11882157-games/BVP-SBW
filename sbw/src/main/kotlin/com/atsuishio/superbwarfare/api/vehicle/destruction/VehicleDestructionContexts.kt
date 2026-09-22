package com.atsuishio.superbwarfare.api.vehicle.destruction

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity
import net.minecraft.resources.ResourceLocation

/** Common-side crush-policy registry; execution remains server-owned by the wreck entity. */
object VehicleDestructionContexts {
    private val crushPolicies = OrderedProviderRegistry<ResourceLocation, TurretWreckCrushPolicy>()

    @JvmStatic
    fun registerCrushPolicy(id: ResourceLocation, policy: TurretWreckCrushPolicy) =
        crushPolicies.register(id, policy)

    @JvmStatic
    fun unregisterCrushPolicy(id: ResourceLocation): Boolean = crushPolicies.unregister(id)

    @JvmStatic
    fun applyCrushPolicy(wreck: TurretWreckEntity, id: ResourceLocation) {
        val policy = crushPolicies[id] ?: return
        try {
            policy.apply(wreck)
        } catch (exception: RuntimeException) {
            Mod.LOGGER.warn("Turret wreck crush policy {} failed for {}", id, wreck, exception)
        }
    }
}
