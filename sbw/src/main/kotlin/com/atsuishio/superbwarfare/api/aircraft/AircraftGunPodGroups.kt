package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSelection
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** A selectable group is an alias only. Every real pod keeps its own ammunition and schedule key. */
object AircraftGunPodGroups {
    const val GROUP = "AircraftGunPods"
    const val MAX_GROUP_MEMBERS = 16
    fun equipped(vehicle: VehicleEntity): List<String> = AircraftArmamentManager.gunPodChannels(vehicle, true)
    fun isPod(vehicle: VehicleEntity, name: String) = name in AircraftArmamentManager.gunPodChannels(vehicle, false)
    fun loaded(vehicle: VehicleEntity): List<String> = equipped(vehicle).filter { (vehicle.gunDataMap[it]?.ammo?.get() ?: 0) > 0 }
    fun data(vehicle: VehicleEntity): GunData? {
        val members = loaded(vehicle)
        val first = members.firstOrNull()?.let { vehicle.gunDataMap[it] } ?: return null
        return first.copy().also { alias ->
            alias.vehicleWeaponIdentity = GROUP
            alias.ammo.set(members.sumOf { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 })
        }
    }
    fun presentation(vehicle: VehicleEntity): AircraftWeaponPresentation? {
        val members = equipped(vehicle)
        if (members.isEmpty()) return null
        return AircraftWeaponPresentation("CNN", "Gun pods", members.sumOf { vehicle.gunDataMap[it]?.ammo?.get() ?: 0 },
            members.sumOf { vehicle.gunDataMap[it]?.get(GunProp.MAGAZINE) ?: 0 })
    }
    /** Equipment identity includes empty feeds; filtering live ammo happens only at event admission. */
    fun bank(selection: VehicleWeaponSelection): List<String>? {
        if (selection.seatIndex != 0) return null
        val vehicle = selection.vehicle
        val definition = AircraftArmamentManager.definition(vehicle) ?: return null
        if (AircraftArmamentManager.gunPodChannels(vehicle, false).isEmpty()) return null
        val builtIn = definition.getAsJsonArray("BuiltInWeapons")?.map { it.asString } ?: emptyList()
        return members(selection.weaponName, builtIn, equipped(vehicle))
    }
    internal fun members(trigger: String, builtIn: List<String>, pods: List<String>): List<String>? = when {
        trigger == GROUP -> pods.distinct()
        trigger in builtIn -> (builtIn + pods).distinct()
        else -> null
    }
}
