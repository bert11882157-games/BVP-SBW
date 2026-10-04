package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.google.gson.JsonObject
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

data class AircraftWeaponPresentation(val category: String, val name: String, val ammo: Int, val capacity: Int,
                                      val guidance: String = "")

/** Stable virtual channels use the native slot/scheduler; equipment owns their ammunition. */
object AircraftStoreWeapons {
    const val PREFIX = "AircraftStore:"
    /** Owner 2026-09-30: every store (bombs, missiles, all) can be released again after 0.1 s. */
    const val RELEASE_INTERVAL_TICKS = 2
    const val RELEASE_RPM = 60 * 20 / RELEASE_INTERVAL_TICKS

    /**
     * A private copy of a launch profile at the store release rate (the shared profile stays untouched): the whole
     * load is one magazine with no reload between releases (the TV launch profiles hold 1 round and a 20 s reload).
     */
    private fun withReleaseRate(profile: DefaultGunData, capacity: Int): DefaultGunData = runCatching {
        val json = com.atsuishio.superbwarfare.data.DataLoader.JSON
        json.decodeFromJsonElement(DefaultGunData.serializer(),
            json.encodeToJsonElement(DefaultGunData.serializer(), profile)).apply {
            rpm = RELEASE_RPM
            magazine = maxOf(1, capacity)
            emptyReloadTime = 0
            normalReloadTime = 0
        }
    }.getOrElse { profile }
    private data class Cached(val store: JsonObject, val capacity: Int, val data: GunData)
    private val cache = WeakHashMap<VehicleEntity, MutableMap<String, Cached>>()
    fun mountId(weapon: String): String? = weapon.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
    internal fun launchable(store: JsonObject): Boolean = when (store["Category"]?.asString) {
        "LASER_GUIDED", "COMMAND_GUIDED" -> true
        "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION" -> store.has("Guidance")
        "BOMB" -> store.has("Bomb")
        "CRUISE" -> store.has("Flight")
        else -> false
    }
    fun ids(vehicle: VehicleEntity, seat: Int, native: List<String>): List<String> {
        if (seat != 0) return native
        val definition = AircraftArmamentManager.definition(vehicle) ?: return native
        return native + AircraftArmamentRegistry.mounts(definition).map { PREFIX + it["Id"].asString } +
            AircraftArmamentManager.gunPodAliases(vehicle)
    }
    fun available(vehicle: VehicleEntity, weapon: String): Boolean {
        val mount = mountId(weapon) ?: return false
        val store = AircraftArmamentManager.equippedStore(vehicle, mount) ?: return false
        return launchable(store) && AircraftArmamentManager.mountRemaining(vehicle, mount) > 0
    }
    fun data(vehicle: VehicleEntity, weapon: String): GunData? {
        if (AircraftGunPodGroups.isAlias(weapon)) return AircraftGunPodGroups.data(vehicle, weapon)
        val mount = mountId(weapon) ?: return null
        val members = AircraftArmamentManager.groupMembers(vehicle, weapon) ?: return null
        val store = AircraftArmamentManager.equippedStore(vehicle, mount) ?: return null
        val capacity = members.sumOf { AircraftArmamentManager.mountCapacity(vehicle, mountId(it) ?: return@sumOf 0) }
        val entries = cache.getOrPut(vehicle) { mutableMapOf() }
        var cached = entries[weapon]
        if (cached?.store !== store || cached?.capacity != capacity) {
            val profile = if (store["Category"]?.asString == "COMMAND_GUIDED") {
                val launch = com.atsuishio.superbwarfare.data.CustomData.GUN_DATA[store["LaunchGunProfile"]?.asString]
                    ?: return null
                withReleaseRate(launch, capacity)
            } else DefaultGunData().apply {
                name = store["Name"].asString; magazine = capacity; rpm = RELEASE_RPM
                projectileAmount = 1; defaultFireMode = "Semi"
            }
            cached = Cached(store, capacity, GunData.from(ItemStack(ModItems.VEHICLE_GUN.get())) { profile })
            entries[weapon] = cached
        }
        return cached.data.also {
            it.vehicleWeaponIdentity = weapon
            val remaining = members.sumOf { AircraftArmamentManager.mountRemaining(vehicle, mountId(it) ?: return@sumOf 0) }
            if (it.ammo.get() != remaining) it.ammo.set(remaining)
        }
    }
    fun clear() = cache.clear()
}
