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
 *
 * WEAPON_SEATS: optionally every seat that may operate them (an attack helicopter whose pilot and gunner both
 * launch: AH-64). Each listed seat selects and releases the shared rack rounds itself, and the round is guided by
 * the occupant who launched it. WEAPON_SEAT stays the primary operator; a definition without WEAPON_SEATS has just
 * that one seat.
 */
object AircraftMissileLaunchers {
    const val CATEGORY = "MISSILE_LAUNCHER"
    const val WEAPON_SEAT = "WeaponSeat"

    @JvmStatic fun weaponSeat(definition: JsonObject?): Int =
        definition?.get(WEAPON_SEAT)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.coerceIn(0, 31) ?: 0

    @JvmStatic fun weaponSeat(vehicle: VehicleEntity): Int = weaponSeat(AircraftArmamentManager.definition(vehicle))

    const val WEAPON_SEATS = "WeaponSeats"

    /** The primary WEAPON_SEAT first, then the other listed WEAPON_SEATS in order, without repeats. */
    @JvmStatic fun weaponSeats(definition: JsonObject?): List<Int> {
        val primary = weaponSeat(definition)
        val listed = definition?.get(WEAPON_SEATS)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt?.takeIf { it in 0..31 } }
            ?: emptyList()
        return (listOf(primary) + listed).distinct()
    }

    @JvmStatic fun isWeaponSeat(definition: JsonObject?, seat: Int): Boolean = seat >= 0 && seat in weaponSeats(definition)

    @JvmStatic fun isWeaponSeat(vehicle: VehicleEntity, seat: Int): Boolean =
        isWeaponSeat(AircraftArmamentManager.definition(vehicle), seat)

    /** The seat whose slot a release by [shooterSeat] reads: its own when it operates the stores, else the primary. */
    @JvmStatic fun releasingSeat(definition: JsonObject?, shooterSeat: Int): Int =
        if (isWeaponSeat(definition, shooterSeat)) shooterSeat else weaponSeat(definition)
}
