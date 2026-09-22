package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

data class AircraftWeaponPresentation(val category: String, val name: String, val ammo: Int, val capacity: Int)

/** Stable virtual channels use the native slot/scheduler; equipment owns their ammunition. */
object AircraftStoreWeapons {
    const val PREFIX = "AircraftStore:"
    private data class Cached(val signature: String, val data: GunData)
    private val cache = WeakHashMap<VehicleEntity, MutableMap<String, Cached>>()
    fun mountId(weapon: String): String? = weapon.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
    fun ids(vehicle: VehicleEntity, seat: Int, native: List<String>): List<String> {
        if (seat != 0) return native
        val definition = AircraftArmamentManager.definition(vehicle) ?: return native
        return native + AircraftArmamentRegistry.mounts(definition).map { PREFIX + it["Id"].asString } +
            if (AircraftArmamentManager.gunPodChannels(vehicle, false).isNotEmpty()) listOf(AircraftGunPodGroups.GROUP) else emptyList()
    }
    fun available(vehicle: VehicleEntity, weapon: String): Boolean {
        val mount = mountId(weapon) ?: return false
        val store = AircraftArmamentManager.equippedStore(vehicle, mount) ?: return false
        return (store["Category"]?.asString == "LASER_GUIDED" ||
            store["Category"]?.asString in setOf("AIR_TO_AIR", "ANTI_RADIATION") && store.has("Guidance") ||
            store["Category"]?.asString == "BOMB" && store.has("Bomb") ||
            store["Category"]?.asString == "CRUISE" && store.has("Flight")) &&
            AircraftArmamentManager.mountRemaining(vehicle, mount) > 0
    }
    fun data(vehicle: VehicleEntity, weapon: String): GunData? {
        if (weapon == AircraftGunPodGroups.GROUP) return AircraftGunPodGroups.data(vehicle)
        val mount = mountId(weapon) ?: return null
        if (!available(vehicle, weapon)) return null
        val store = AircraftArmamentManager.equippedStore(vehicle, mount) ?: return null
        val capacity = AircraftArmamentManager.mountCapacity(vehicle, mount)
        val signature = store.toString() + "|" + capacity
        val entries = cache.getOrPut(vehicle) { mutableMapOf() }
        var cached = entries[weapon]
        if (cached?.signature != signature) {
            val profile = DefaultGunData().apply {
                name = store["Name"].asString; magazine = capacity; rpm = 120
                projectileAmount = 1; defaultFireMode = "Semi"
            }
            cached = Cached(signature, GunData.from(ItemStack(ModItems.VEHICLE_GUN.get())) { profile })
            entries[weapon] = cached
        }
        return cached.data.also {
            it.vehicleWeaponIdentity = weapon
            it.ammo.set(AircraftArmamentManager.mountRemaining(vehicle, mount))
        }
    }
    fun clear() = cache.clear()
}
