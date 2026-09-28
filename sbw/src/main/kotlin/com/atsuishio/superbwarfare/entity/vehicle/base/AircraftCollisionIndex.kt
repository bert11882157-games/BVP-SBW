package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import java.util.WeakHashMap

/** Level-local discovery for aircraft whose physical parts extend beyond vanilla section padding. */
internal object AircraftCollisionIndex {
    private val levels = WeakHashMap<Level, PhysicalBoundsIndex<VehicleEntity>>()

    @Synchronized
    fun update(vehicle: VehicleEntity, bounds: AABB) {
        if (!vehicle.isAddedToWorld || vehicle.isRemoved) return
        levels.getOrPut(vehicle.level()) { PhysicalBoundsIndex() }.update(vehicle, bounds)
    }

    @Synchronized
    fun remove(vehicle: VehicleEntity) {
        levels[vehicle.level()]?.remove(vehicle)
    }

    @JvmStatic
    @Synchronized
    fun query(level: Level, bounds: AABB): List<VehicleEntity> {
        val hits = levels[level]?.query(bounds) ?: return emptyList()
        return if (hits.isEmpty()) hits else hits.filter { !it.isRemoved && it.isAddedToWorld && it.level() === level }
    }
}
