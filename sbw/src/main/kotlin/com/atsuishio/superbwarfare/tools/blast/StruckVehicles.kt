package com.atsuishio.superbwarfare.tools.blast

import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity

/**
 * Vehicles a munition has already hit directly (armor resolved the hit, or an aircraft hit rule charged it), with
 * the hull damage that hit dealt. Its own blast then only adds what the blast would do beyond that: the direct hit
 * already carries the round's damage (owner direction 2026-09-28), but a direct hit never leaves the target better
 * off than a near miss (owner 2026-09-29, "munitions underperforming": a weak direct hit used to cancel a whole
 * bomb-sized blast). Kept in the projectile's persistent data, keyed by vehicle UUID.
 */
object StruckVehicles {
    private const val KEY = "SbwStruckVehicles"
    private const val MAX = 16

    /** Marks [vehicle] as fully charged by the direct hit: its blast adds nothing. */
    @JvmStatic
    fun mark(munition: Entity?, vehicle: Entity?) = mark(munition, vehicle, Double.POSITIVE_INFINITY)

    /** Marks [vehicle] as hit directly for [damage] hull damage (the most of several hits counts). */
    @JvmStatic
    fun mark(munition: Entity?, vehicle: Entity?, damage: Double) {
        if (munition == null || vehicle == null || munition.level().isClientSide) return
        val data = munition.persistentData
        val set = data.getCompound(KEY)
        val key = vehicle.stringUUID
        if (!set.contains(key) && set.size() >= MAX) return
        val amount = if (damage.isNaN() || damage < 0.0) 0.0 else damage
        val previous = if (set.contains(key)) set.getDouble(key) else 0.0
        set.putDouble(key, maxOf(previous, amount))
        data.put(KEY, set)
    }

    @JvmStatic
    fun struck(munition: Entity?, vehicle: Entity): Boolean = directDamage(munition, vehicle) != null

    /** Hull damage [munition] already dealt [vehicle] directly, or null when it did not hit it. */
    @JvmStatic
    fun directDamage(munition: Entity?, vehicle: Entity): Double? {
        val data = munition?.persistentData ?: return null
        if (com.atsuishio.superbwarfare.api.aircraft.AircraftProjectileHitReceipts.contains(data, vehicle.uuid))
            return Double.POSITIVE_INFINITY
        if (!data.contains(KEY, Tag.TAG_COMPOUND.toInt())) return null
        val set = data.getCompound(KEY)
        if (!set.contains(vehicle.stringUUID)) return null
        return set.getDouble(vehicle.stringUUID)
    }

    /** What a blast of [blastDamage] still adds to a vehicle the munition already hit for [direct]. */
    @JvmStatic
    fun remainingBlast(blastDamage: Double, direct: Double?): Double =
        if (direct == null) blastDamage else maxOf(0.0, blastDamage - direct)
}
