package com.atsuishio.superbwarfare.client.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemKind
import com.atsuishio.superbwarfare.data.vehicle.subdata.PassengerWeaponStationWeaponKind

enum class VehicleWeaponHudKind { AUTOCANNON, TANK_CANNON, HMG, LMG, ATGM, GRENADE_LAUNCHER, UNKNOWN }

/** Presentation categories from typed metadata only; display/channel names are never classifiers. */
object VehicleWeaponHudMetadata {
    fun kind(
        stationKind: PassengerWeaponStationWeaponKind?,
        hullDamageClass: ProjectileHullDamageClass?,
        projectileType: String?,
        authoredKind: WeaponSystemKind? = null,
        caliberMm: Double? = null,
    ): VehicleWeaponHudKind {
        if (projectileType == "superbwarfare:gun_grenade") return VehicleWeaponHudKind.GRENADE_LAUNCHER
        if (authoredKind != null) return when (authoredKind) {
            WeaponSystemKind.AUTOCANNON -> VehicleWeaponHudKind.AUTOCANNON
            WeaponSystemKind.TANK_CANNON -> VehicleWeaponHudKind.TANK_CANNON
            WeaponSystemKind.HMG -> VehicleWeaponHudKind.HMG
            WeaponSystemKind.LMG -> VehicleWeaponHudKind.LMG
            WeaponSystemKind.ATGM -> VehicleWeaponHudKind.ATGM
            WeaponSystemKind.UNKNOWN -> VehicleWeaponHudKind.UNKNOWN
        }
        when (stationKind) {
            PassengerWeaponStationWeaponKind.HEAVY_MACHINE_GUN -> return VehicleWeaponHudKind.HMG
            PassengerWeaponStationWeaponKind.AUTOCANNON -> return VehicleWeaponHudKind.AUTOCANNON
            PassengerWeaponStationWeaponKind.LOW_PRESSURE_CANNON -> return VehicleWeaponHudKind.TANK_CANNON
            else -> Unit
        }
        // Native launcher families are exact ids, not substrings of a weapon or round name.
        return when (projectileType) {
            "superbwarfare:cannon_shell" -> VehicleWeaponHudKind.TANK_CANNON
            "superbwarfare:small_cannon_shell" -> VehicleWeaponHudKind.AUTOCANNON
            "superbwarfare:projectile" -> when {
                caliberMm == null || !caliberMm.isFinite() || caliberMm <= 0 -> VehicleWeaponHudKind.UNKNOWN
                caliberMm < 12 -> VehicleWeaponHudKind.LMG
                else -> VehicleWeaponHudKind.HMG
            }
            else -> if (hullDamageClass == ProjectileHullDamageClass.ATGM)
                VehicleWeaponHudKind.ATGM else VehicleWeaponHudKind.UNKNOWN
        }
    }

    /** Preserve seat slot identities even when unavailable loadout entries leave holes. */
    fun equippedIndices(names: List<String>, available: (String) -> Boolean): List<Int> =
        names.indices.filter { names[it].isNotBlank() && available(names[it]) }

    fun supportsAmmoCycle(selectableAmmoCount: Int): Boolean = selectableAmmoCount > 1
}

data class VehicleWeaponHudAmmo(val loaded: Int?, val reserve: Int?, val infinite: Boolean) {
    companion object {
        fun from(magazineSize: Int, loaded: Int, reserve: Int, infiniteConsumer: Boolean): VehicleWeaponHudAmmo {
            val infinite = infiniteConsumer || reserve == Int.MAX_VALUE
            return VehicleWeaponHudAmmo(
                loaded.takeIf { magazineSize > 0 && it >= 0 },
                reserve.takeIf { !infinite && it >= 0 },
                infinite,
            )
        }
    }
}
