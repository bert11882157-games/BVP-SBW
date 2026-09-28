package com.atsuishio.superbwarfare.tools.blast

import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity

/**
 * Vehicles a munition has already hit directly (armor resolved the hit, or an aircraft hit rule charged it). Its
 * own blast then leaves that vehicle alone: the direct hit already carries the round's damage (owner direction
 * 2026-09-28). Kept in the projectile's persistent data, keyed by vehicle UUID.
 */
object StruckVehicles {
    private const val KEY = "SbwStruckVehicles"
    private const val MAX = 16

    @JvmStatic
    fun mark(munition: Entity?, vehicle: Entity?) {
        if (munition == null || vehicle == null || munition.level().isClientSide) return
        val data = munition.persistentData
        val set = data.getCompound(KEY)
        if (set.size() >= MAX) return
        set.putBoolean(vehicle.stringUUID, true)
        data.put(KEY, set)
    }

    @JvmStatic
    fun struck(munition: Entity?, vehicle: Entity): Boolean {
        val data = munition?.persistentData ?: return false
        if (data.contains(KEY, Tag.TAG_COMPOUND.toInt()) && data.getCompound(KEY).contains(vehicle.stringUUID)) return true
        return com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileHitReceipts.contains(data, vehicle.uuid)
    }
}
