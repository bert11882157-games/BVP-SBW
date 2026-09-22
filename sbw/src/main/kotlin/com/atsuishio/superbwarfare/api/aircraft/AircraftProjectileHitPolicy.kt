package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import java.util.UUID

/** Direct aircraft HP, independent of armor, round damage, and lightly-armored multipliers. */
object AircraftProjectileHitPolicy {
    @JvmStatic
    fun appliesTo(type: VehicleType?): Boolean = when (type) {
        VehicleType.AIRPLANE, VehicleType.HELICOPTER, VehicleType.DRONE -> true
        else -> false
    }

    @JvmStatic
    fun damageForCaliberMillimetres(caliber: Double?): Float? = when {
        caliber == null || !caliber.isFinite() || caliber < 1.0 -> null
        caliber < 12.7 -> 1f
        caliber < 20.0 -> 2.5f
        caliber < 30.0 -> 6f
        caliber < 50.0 -> 9f
        caliber < 80.0 -> 30f
        else -> 100f
    }

    /** Explicit family HP is authoritative even when a projectile also carries combat metadata. */
    internal fun resolveDamage(caliber: Double?, directOverride: Float?): Float? =
        if (directOverride != null) directOverride.takeIf { it.isFinite() && it > 0f }
        else damageForCaliberMillimetres(caliber)

    internal fun nativeRoute(hasReceipt: Boolean, explosion: Boolean, directProjectile: Boolean) = when {
        hasReceipt -> AircraftProjectileDamageRoute.DUPLICATE
        explosion || !directProjectile -> AircraftProjectileDamageRoute.UNRELATED
        else -> AircraftProjectileDamageRoute.PHYSICAL_HIT
    }
}

internal enum class AircraftProjectileDamageRoute { UNRELATED, DUPLICATE, PHYSICAL_HIT }

/**
 * Server-only receipts live in the projectile's persistent data, never on a world/global cache.
 * One round can damage each aircraft once; multi-volume hits and that round's subsequent blast
 * cannot charge the same aircraft again. Entity UUIDs prevent numeric-ID reuse across targets.
 * A bounded full or malformed receipt set fails closed for further aircraft damage only.
 */
internal object AircraftProjectileHitReceipts {
    internal const val KEY = "SBWAircraftProjectileHits"
    internal const val MAX_TARGETS = 64

    fun contains(data: CompoundTag, target: UUID): Boolean {
        if (!data.contains(KEY)) return false
        if (!data.contains(KEY, Tag.TAG_COMPOUND.toInt())) return true
        val receipts = data.getCompound(KEY)
        return receipts.contains(target.toString()) || receipts.size() >= MAX_TARGETS
    }

    fun claim(data: CompoundTag, target: UUID): Boolean {
        if (contains(data, target)) return false
        val receipts = data.getCompound(KEY)
        receipts.putBoolean(target.toString(), true)
        data.put(KEY, receipts)
        return true
    }
}
