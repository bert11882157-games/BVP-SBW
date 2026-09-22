package com.atsuishio.superbwarfare.api.vehicle.action

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleSecondaryFireAction
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponActionIds
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightActionIds
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation

/** Common-side action definitions; all mutable session state remains server-owned per vehicle. */
object VehicleActionRegistry {
    private val factories = OrderedProviderRegistry<ResourceLocation, VehicleActionFactory>()

    init {
        // Core secondary fire uses the existing edge/action transport.  VehicleEntity derives
        // ordered slot candidates from the seat weapon list; pair metadata remains optional.
        register(VehicleWeaponActionIds.FIRE_SECONDARY) { vehicle ->
            VehicleSecondaryFireAction(vehicle)
        }
        register(VehicleFlightActionIds.LANDING_GEAR) { vehicle ->
            if (vehicle.hasFixedWingLandingGear()) FixedWingLandingGearAction() else null
        }
    }

    @JvmStatic
    fun register(id: ResourceLocation, factory: VehicleActionFactory) = factories.register(id, factory)

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = factories.unregister(id)

    @JvmStatic
    fun isRegistered(id: ResourceLocation): Boolean = factories[id] != null

    internal fun create(vehicle: VehicleEntity, id: ResourceLocation): VehicleAction? {
        val factory = factories[id] ?: return null
        return try {
            factory.create(vehicle)
        } catch (exception: RuntimeException) {
            Mod.LOGGER.warn("Vehicle action factory {} failed for {}", id, vehicle, exception)
            null
        }
    }
}
