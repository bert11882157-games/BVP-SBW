package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject

/**
 * Pylon launchers for an aircraft's own guided weapons (helicopter ATGM racks): fitting one enables the native weapon
 * channel named in the mount's NativeWeaponIds, exactly as a rocket or gun pod enables its channel. The missiles keep
 * their native behaviour (seat, lock-on, camera guidance, magazine and reload from the aircraft inventory); the store
 * only decides whether the launcher is carried. Nothing is bought at fitting and nothing is released by the store.
 *
 * WEAPON_SEAT: an armament definition may name the seat whose occupant operates its released guided stores (fires
 * them, designates, steers command-guided rounds); 0, the pilot, when absent. Loadout editing stays with the pilot.
 */
object AircraftMissileLaunchers {
    const val CATEGORY = "MISSILE_LAUNCHER"
    const val WEAPON_SEAT = "WeaponSeat"

    @JvmStatic fun weaponSeat(definition: JsonObject?): Int =
        definition?.get(WEAPON_SEAT)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.coerceIn(0, 31) ?: 0

    @JvmStatic fun weaponSeat(vehicle: VehicleEntity): Int = weaponSeat(AircraftArmamentManager.definition(vehicle))
}
