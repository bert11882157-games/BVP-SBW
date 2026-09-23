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
    private val aliases = java.util.WeakHashMap<VehicleEntity, MutableMap<String, Alias>>()
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
        if (selection.seatIndex != 0) return null
        val vehicle = selection.vehicle
        val definition = AircraftArmamentManager.definition(vehicle) ?: return null
        val builtIn = definition.getAsJsonArray("BuiltInWeapons")?.map { it.asString } ?: emptyList()
        return if (isAlias(selection.weaponName)) members(vehicle, selection.weaponName)
        else members(selection.weaponName, builtIn, emptyList())
    }
    internal fun members(trigger: String, builtIn: List<String>, pods: List<String>): List<String>? = when {
        trigger == GROUP -> pods.distinct()
        trigger in builtIn -> builtIn.distinct()
        else -> null
    }
}
