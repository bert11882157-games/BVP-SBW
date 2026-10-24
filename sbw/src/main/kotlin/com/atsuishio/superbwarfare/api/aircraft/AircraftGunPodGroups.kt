package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSelection
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** A selectable group is an alias only. Every real pod keeps its own ammunition and schedule key. */
object AircraftGunPodGroups {
    const val GROUP = "AircraftGunPods"
    const val MAX_GROUP_MEMBERS = 16
    private data class Alias(val source: GunData, val tick: Int, val identity: String, val data: GunData)
    // Identity weak keys: shared by the render and integrated-server threads and by both copies of one vehicle.
    private val aliases: MutableMap<VehicleEntity, MutableMap<String, Alias>> =
        com.google.common.collect.MapMaker().weakKeys().makeMap()
    fun equipped(vehicle: VehicleEntity): List<String> = AircraftArmamentManager.gunPodChannels(vehicle, true)
    fun isPod(vehicle: VehicleEntity, name: String) = name in AircraftArmamentManager.gunPodChannels(vehicle, false)
    fun isAlias(name: String): Boolean = name == GROUP || name.startsWith("$GROUP:")
    fun members(vehicle: VehicleEntity, alias: String): List<String> =
        AircraftArmamentManager.groupMembers(vehicle, alias) ?: emptyList()
    fun loaded(vehicle: VehicleEntity, alias: String = GROUP): List<String> =
        members(vehicle, alias).filter { (vehicle.gunDataMap[it]?.ammo?.get() ?: 0) > 0 }
    fun data(vehicle: VehicleEntity, identity: String = GROUP): GunData? {
        val members = members(vehicle, identity)
        val first = members.firstOrNull()?.let { vehicle.gunDataMap[it] } ?: return null
        val entries = aliases.getOrPut(vehicle) { mutableMapOf() }
        val previous = entries[identity]
        val data = if (previous?.source === first && previous.tick == vehicle.tickCount) previous.data
            else first.copy().also { entries[identity] = Alias(first, vehicle.tickCount, identity, it) }
        return data.also { alias ->
            alias.vehicleWeaponIdentity = identity
            val remaining = members.sumOf { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 }
            if (alias.ammo.get() != remaining) alias.ammo.set(remaining)
        }
    }
    fun presentation(vehicle: VehicleEntity, identity: String = GROUP): AircraftWeaponPresentation? {
        val members = members(vehicle, identity)
        if (members.isEmpty()) return null
        return AircraftWeaponPresentation("CNN", "Gun pods", members.sumOf { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 },
            members.sumOf { vehicle.gunDataMap[it]?.get(GunProp.MAGAZINE) ?: 0 })
    }
    /** Equipment identity includes empty feeds; filtering live ammo happens only at event admission. */
    fun bank(selection: VehicleWeaponSelection): List<String>? {
        val vehicle = selection.vehicle
        val definition = AircraftArmamentManager.definition(vehicle) ?: return null
        // a pod alias fires its pods from whichever seat selected it (AircraftStoreWeapons.ids offers it); the
        // built-in gun bank stays the pilot's
        if (isAlias(selection.weaponName)) return members(vehicle, selection.weaponName)
        if (selection.seatIndex != 0) return null
        val builtIn = definition.getAsJsonArray("BuiltInWeapons")?.map { it.asString } ?: emptyList()
        val bank = members(selection.weaponName, builtIn, emptyList()) ?: return null
        val own = vehicle.getWeaponIds(0)
        return pilotBank(bank, selection.weaponName) { name ->
            val index = own.indexOf(name)
            index >= 0 && pilotBankable(vehicle, index, name)
        }
    }

    /**
     * The built-in guns the pilot's trigger fans together: those [bankable] from the pilot's seat, or none when the
     * selected gun is not one of them (it then fires on its own). A built-in gun the pilot cannot bank used to refuse
     * the whole bank, so the trigger never fired: the Il-10's RearCannon (listed only on the gunner's seat) silenced
     * its four wing cannons, and a gunner's station gun selected by the pilot (AH-64D M230, AH-1F, Mi-24A/D) never
     * fired from the pilot's seat.
     */
    internal fun pilotBank(builtIn: List<String>, selected: String, bankable: (String) -> Boolean): List<String>? =
        builtIn.filter(bankable).takeIf { selected in it }

    /**
     * A turret gun (Barrel / Turret: a helicopter's chin gun, aimed by its turret) fires on its own, as does a station
     * gun on a seat that does not control the station; the bank fans hull-fixed guns, and station guns of the seat
     * that aims them ([com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleFixedGunBanks] refuses the others).
     */
    private fun pilotBankable(vehicle: VehicleEntity, index: Int, name: String): Boolean =
        when (vehicle.gunDataMap[name]?.get(GunProp.SHOOT_POS)?.transform) {
            "Vehicle" -> true
            "WeaponStationBarrel" -> vehicle.isHullParentedPassengerWeaponStation() &&
                vehicle.isPassengerWeaponStationWeapon(0, index)
            else -> false
        }
    internal fun members(trigger: String, builtIn: List<String>, pods: List<String>): List<String>? = when {
        trigger == GROUP -> pods.distinct()
        trigger in builtIn -> builtIn.distinct()
        else -> null
    }
}
