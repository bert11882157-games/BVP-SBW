package com.atsuishio.superbwarfare.api.vehicle.module

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.resources.ResourceLocation

/** Storage adapter used by a module definition. Generic modules use SBW's module-state map. */
enum class VehicleModuleAdapter {
    GENERIC,
    LEGACY_TURRET,
    LEGACY_RUNNING_GEAR_LEFT,
    LEGACY_RUNNING_GEAR_RIGHT,
    LEGACY_ENGINE_MAIN,
    LEGACY_ENGINE_SUB,
}

/** Immutable module schema. maxHealth is the logical health exposed to addon policy and HUDs. */
data class VehicleModuleDefinition @JvmOverloads constructor(
    val id: ResourceLocation,
    val maxHealth: Float,
    val adapter: VehicleModuleAdapter = VehicleModuleAdapter.GENERIC,
)

/** Immutable authoritative module state. */
data class VehicleModuleState(
    val id: ResourceLocation,
    val maxHealth: Float,
    val health: Float,
    val destroyed: Boolean,
)

fun interface VehicleModuleDefinitionProvider {
    /** Return null to let the next provider or native legacy definition handle the ID. */
    fun resolve(vehicle: VehicleEntity, id: ResourceLocation): VehicleModuleDefinition?
}
