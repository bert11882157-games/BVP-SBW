package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.world.item.ItemStack
import java.security.MessageDigest
import java.util.Base64
import java.util.WeakHashMap

data class AircraftWeaponPresentation(val category: String, val name: String, val ammo: Int, val capacity: Int)

/** One selectable identity per store type; mounts retain their individual ammunition and targets. */
object AircraftStoreWeapons {
    const val PREFIX = "AircraftStore:"
    const val GROUP_PREFIX = "AircraftStoreGroup:"
    data class Member(val mountId: String, val nativeWeapons: List<String>, val capacity: Int, val ammo: Int)
    data class Group(val storeId: String, val store: JsonObject, val members: List<Member>,
                     val nativeWeapons: List<String>, val capacity: Int, val ammo: Int) {
        val weaponId get() = groupId(storeId)
        val virtual get() = virtual(store)
        val next get() = members.firstOrNull { it.ammo > 0 }
    }
    internal data class Equipped(val storeId: String, val store: JsonObject, val member: Member)
    private data class Cached(val signature: String, val data: GunData)
    private val cache = WeakHashMap<VehicleEntity, MutableMap<String, Cached>>()
    fun mountId(weapon: String): String? = weapon.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
    fun groupId(storeId: String): String {
        val readable = GROUP_PREFIX + storeId
        if (readable.length <= 80) return readable
        // Optional FFA seeker channels have an 80-character ABI limit; the full resource ID
        // remains in Group.storeId, while long selector/channel identities use a stable digest.
        val digest = MessageDigest.getInstance("SHA-256").digest(storeId.toByteArray(Charsets.UTF_8))
        return GROUP_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
    fun isChannel(weapon: String) = weapon.startsWith(GROUP_PREFIX) || mountId(weapon) != null
    private fun virtual(store: JsonObject) = AircraftStoreControls.selectable(
        store["Category"]?.asString, store.has("Guidance"), 0)

    /** Shared native feeds count once, irrespective of the number of mount mappings. */
    internal fun collect(equipped: List<Equipped>, native: (String) -> Pair<Int, Int>?): List<Group> =
        equipped.groupBy { it.storeId }.mapNotNull { (id, entries) ->
            val store = entries.first().store
            val members = entries.map { it.member }
            val channels = members.flatMap { it.nativeWeapons }.distinct()
            val isVirtual = virtual(store)
            if (!isVirtual && (store["Category"]?.asString !in setOf("GUN_POD", "ROCKET_POD", "BOMB") || channels.isEmpty()))
                return@mapNotNull null
            val feeds = channels.mapNotNull(native)
            Group(id, store, members, channels,
                if (isVirtual) members.sumOf { it.capacity } else feeds.sumOf { it.first },
                if (isVirtual) members.sumOf { it.ammo } else feeds.sumOf { it.second })
        }

    fun groups(vehicle: VehicleEntity): List<Group> {
        val definition = AircraftArmamentManager.definition(vehicle) ?: return emptyList()
        val entries = AircraftArmamentRegistry.mounts(definition).mapNotNull { mount ->
            val id = mount["Id"].asString
            val storeId = AircraftArmamentManager.equippedStoreId(vehicle, id) ?: return@mapNotNull null
            val store = AircraftArmamentManager.equippedStore(vehicle, id) ?: return@mapNotNull null
            Equipped(storeId, store, Member(id, AircraftArmamentManager.nativeWeapons(mount, storeId),
                AircraftArmamentManager.mountCapacity(vehicle, id), AircraftArmamentManager.mountRemaining(vehicle, id)))
        }
        return collect(entries) { channel -> vehicle.gunDataMap[channel]?.let { it.get(GunProp.MAGAZINE) to it.ammo.get() } }
    }
    fun group(vehicle: VehicleEntity, weapon: String): Group? {
        val legacyMount = mountId(weapon)
        return groups(vehicle).firstOrNull { it.weaponId == weapon || legacyMount != null && it.members.any { member -> member.mountId == legacyMount } }
    }
    fun ids(vehicle: VehicleEntity, seat: Int, native: List<String>): List<String> {
        if (seat != 0) return native
        val definition = AircraftArmamentManager.definition(vehicle) ?: return native
        return channelIds(AircraftArmamentRegistry.mounts(definition), native)
    }
    internal fun channelIds(mounts: List<JsonObject>, native: List<String>): List<String> {
        // Reserve old channel indices and every authored store type, including unfitted ones.
        // Equipment/ammo changes then cannot move another selected weapon to a different index.
        return native + mounts.map { PREFIX + it["Id"].asString } + listOf(AircraftGunPodGroups.GROUP) +
            mounts.flatMap { it.getAsJsonArray("AllowedStores")?.map { id -> id.asString }.orEmpty() }
                .distinct().map(::groupId)
    }
    fun available(vehicle: VehicleEntity, weapon: String): Boolean = group(vehicle, weapon) != null

    /** Native stores retain the actual channel's shot transaction, mutation and rejection. */
    internal fun launchNative(group: Group, ammunition: (String) -> Int, shoot: (String) -> ShotResult): ShotResult {
        val channel = group.nativeWeapons.firstOrNull { ammunition(it) > 0 }
            ?: return ShotResult.rejected(ShotRejectionReason.CANNOT_SHOOT, group.weaponId)
        return shoot(channel).withWeaponName(group.weaponId)
    }

    fun data(vehicle: VehicleEntity, weapon: String): GunData? {
        val group = group(vehicle, weapon) ?: return null
        val name = group.store["Name"]?.asString ?: group.storeId
        if (!group.virtual) {
            val first = group.nativeWeapons.mapNotNull { vehicle.gunDataMap[it] }.let { feeds ->
                feeds.firstOrNull { it.ammo.get() > 0 } ?: feeds.firstOrNull()
            } ?: return null
            return first.copy().also { alias ->
                val override = alias.propertyOverrideString.get().takeIf { it.isNotBlank() }
                    ?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
                override.addProperty("Name", name)
                override.addProperty("Magazine", group.capacity)
                alias.propertyOverrideString.set(override.toString())
                alias.vehicleWeaponIdentity = group.weaponId
                alias.ammo.set(group.ammo)
            }
        }
        val signature = group.store.toString() + "|" + group.capacity
        val entries = cache.getOrPut(vehicle) { mutableMapOf() }
        var cached = entries[weapon]
        if (cached?.signature != signature) {
            val profile = DefaultGunData().apply {
                this.name = name; magazine = group.capacity; rpm = 120
                projectileAmount = 1; defaultFireMode = "Semi"
            }
            cached = Cached(signature, GunData.from(ItemStack(ModItems.VEHICLE_GUN.get())) { profile })
            entries[weapon] = cached
        }
        return cached.data.also { it.vehicleWeaponIdentity = group.weaponId; it.ammo.set(group.ammo) }
    }
    fun clear() = cache.clear()
}
