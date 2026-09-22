package com.atsuishio.superbwarfare.api.vehicle.module

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation

/** Ordered common-side module-schema extension point. */
object VehicleModuleProviders {
    private val definitionProviders = OrderedProviderRegistry<ResourceLocation, VehicleModuleDefinitionProvider>()

    @JvmStatic
    fun registerDefinitionProvider(id: ResourceLocation, provider: VehicleModuleDefinitionProvider) =
        definitionProviders.register(id, provider)

    @JvmStatic
    fun unregisterDefinitionProvider(id: ResourceLocation): Boolean = definitionProviders.unregister(id)

    @JvmStatic
    fun resolve(vehicle: VehicleEntity, id: ResourceLocation): VehicleModuleDefinition? {
        com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.definition(vehicle, id)?.let { return it }
        for ((providerId, provider) in definitionProviders.snapshot()) {
            try {
                provider.resolve(vehicle, id)?.let { return it.sanitized() }
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Vehicle module definition provider {} failed for {} on {}", providerId, id, vehicle, exception)
            }
        }
        return null
    }

    private fun VehicleModuleDefinition.sanitized(): VehicleModuleDefinition {
        return if (maxHealth.isFinite() && maxHealth > 0f) this else copy(maxHealth = 1f)
    }
}
