package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleAdapter
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleProviders
import net.minecraft.resources.ResourceLocation

/** Compatibility bridge: virtual addon methods and existing synchronized accessors stay intact. */
internal class VehicleEntityModuleStateAccess(private val vehicle: VehicleEntity) : VehicleModuleStateAccess {
    override val isClientSide get() = vehicle.level().isClientSide
    override fun providedDefinition(id: ResourceLocation) = VehicleModuleProviders.resolve(vehicle, id)
    override fun definition(id: ResourceLocation) = vehicle.getVehicleModuleDefinition(id)
    override fun state(id: ResourceLocation) = vehicle.getVehicleModuleState(id)
    override fun setState(id: ResourceLocation, health: Double, destroyed: Boolean) =
        vehicle.setVehicleModuleState(id, health, destroyed)

    override fun legacyMaximum(adapter: VehicleModuleAdapter): Float = when (adapter) {
        VehicleModuleAdapter.LEGACY_TURRET -> vehicle.getTurretMaxHealth()
        VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT,
        VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> vehicle.getWheelMaxHealth()
        VehicleModuleAdapter.LEGACY_ENGINE_MAIN,
        VehicleModuleAdapter.LEGACY_ENGINE_SUB -> vehicle.getEngineMaxHealth()
        VehicleModuleAdapter.GENERIC -> error("Generic modules do not use legacy storage")
    }

    override fun legacyState(adapter: VehicleModuleAdapter): LegacyVehicleModuleState = when (adapter) {
        VehicleModuleAdapter.LEGACY_TURRET -> LegacyVehicleModuleState(vehicle.turretHealth, vehicle.turretDamaged)
        VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT -> LegacyVehicleModuleState(vehicle.leftWheelHealth, vehicle.leftWheelDamaged)
        VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> LegacyVehicleModuleState(vehicle.rightWheelHealth, vehicle.rightWheelDamaged)
        VehicleModuleAdapter.LEGACY_ENGINE_MAIN -> LegacyVehicleModuleState(vehicle.mainEngineHealth, vehicle.mainEngineDamaged)
        VehicleModuleAdapter.LEGACY_ENGINE_SUB -> LegacyVehicleModuleState(vehicle.subEngineHealth, vehicle.subEngineDamaged)
        VehicleModuleAdapter.GENERIC -> error("Generic modules do not use legacy storage")
    }

    override fun writeLegacyState(adapter: VehicleModuleAdapter, health: Float, destroyed: Boolean) {
        when (adapter) {
            VehicleModuleAdapter.LEGACY_TURRET -> {
                vehicle.turretHealth = health
                vehicle.turretDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT -> {
                vehicle.leftWheelHealth = health
                vehicle.leftWheelDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT -> {
                vehicle.rightWheelHealth = health
                vehicle.rightWheelDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_ENGINE_MAIN -> {
                vehicle.mainEngineHealth = health
                vehicle.mainEngineDamaged = destroyed
            }
            VehicleModuleAdapter.LEGACY_ENGINE_SUB -> {
                vehicle.subEngineHealth = health
                vehicle.subEngineDamaged = destroyed
            }
            VehicleModuleAdapter.GENERIC -> error("Generic modules do not use legacy storage")
        }
    }

    override fun snapshot(): String = vehicle.entityData.get(VehicleEntity.MODULE_STATE_SNAPSHOT)
    override fun publishSnapshot(payload: String) = vehicle.publishModuleStateSnapshot(payload)
}
