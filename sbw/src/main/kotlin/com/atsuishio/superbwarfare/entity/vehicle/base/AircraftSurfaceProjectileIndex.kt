package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.WeakHashMap

/** Projectile-only admission for outer surfaces beyond the physical fuselage envelope. */
object AircraftSurfaceProjectileIndex {
    private val levels=WeakHashMap<Level,PhysicalBoundsIndex<VehicleEntity>>()

    @Synchronized fun update(vehicle:VehicleEntity) {
        if(vehicle.level().isClientSide || !vehicle.isAddedToWorld || vehicle.isRemoved) return
        val bounds=AircraftSurfaceModules.discoveryBounds(vehicle)
        if(bounds==null) { remove(vehicle);return }
        levels.getOrPut(vehicle.level()) { PhysicalBoundsIndex() }.update(vehicle,bounds)
    }
    @Synchronized fun remove(vehicle:VehicleEntity) { levels[vehicle.level()]?.remove(vehicle) }
    @JvmStatic @Synchronized fun query(level:Level,bounds:AABB):List<VehicleEntity> {
        if(level.isClientSide) return emptyList()
        val hits=levels[level]?.query(bounds) ?: return emptyList()
        return if(hits.isEmpty()) hits else hits.filter { !it.isRemoved && it.isAddedToWorld && it.level()===level }
    }
}
