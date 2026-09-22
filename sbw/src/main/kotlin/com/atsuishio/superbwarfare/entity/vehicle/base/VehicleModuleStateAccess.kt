package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleAdapter
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleDefinition
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleState
import net.minecraft.resources.ResourceLocation

/** Module-only access to legacy storage, replication, and addon overrides. */
internal interface VehicleModuleStateAccess {
    val isClientSide: Boolean
    fun providedDefinition(id: ResourceLocation): VehicleModuleDefinition?
    fun definition(id: ResourceLocation): VehicleModuleDefinition?
    fun state(id: ResourceLocation): VehicleModuleState?
    fun setState(id: ResourceLocation, health: Double, destroyed: Boolean): VehicleModuleState?
    fun legacyMaximum(adapter: VehicleModuleAdapter): Float
    fun legacyState(adapter: VehicleModuleAdapter): LegacyVehicleModuleState
    fun writeLegacyState(adapter: VehicleModuleAdapter, health: Float, destroyed: Boolean)
    fun snapshot(): String
    fun publishSnapshot(payload: String)
}

/** Native storage units, before conversion to an addon's logical module maximum. */
internal data class LegacyVehicleModuleState(val health: Float, val destroyed: Boolean)
