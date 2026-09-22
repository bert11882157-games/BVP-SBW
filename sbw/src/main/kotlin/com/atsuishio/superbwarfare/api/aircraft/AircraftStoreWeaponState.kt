package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject

/**
 * One vehicle's derived loadout, used only on that entity's logical thread. Definition identity
 * and catalogue revision invalidate the layout; equipment revision invalidates fitted groups.
 * Native feed counts remain live: firing/reloading a pod need not publish an armament revision.
 * Retains only the current layout/loadout and one seat-zero channel list, never entity references.
 */
internal class AircraftStoreWeaponState {
    private var definition: JsonObject? = null
    private var catalogue = Long.MIN_VALUE
    private var equipment = Long.MIN_VALUE
    private var fitted = emptyList<AircraftStoreWeapons.Group>()
    private var byChannel = emptyMap<String, Int>()
    private var nativeIds = emptyList<String>()
    private var ids = emptyList<String>()
    private var reserved = emptyList<String>()
    var mounts: List<JsonObject> = emptyList(); private set
    private var hidden = emptySet<String>()

    fun layout(current: JsonObject?, revision: Long): AircraftStoreWeaponState {
        if (definition === current && catalogue == revision) return this
        definition = current
        catalogue = revision
        equipment = Long.MIN_VALUE
        fitted = emptyList()
        byChannel = emptyMap()
        nativeIds = emptyList()
        ids = emptyList()
        mounts = current?.let(AircraftArmamentRegistry::mounts).orEmpty()
        reserved = if (current == null) emptyList() else AircraftStoreWeapons.channelIds(mounts, emptyList())
        hidden = buildSet {
            add(AircraftGunPodGroups.GROUP)
            for (mount in mounts) {
                mount.getAsJsonArray("AllowedStores")?.forEach {
                    addAll(AircraftArmamentManager.nativeWeapons(mount, it.asString))
                }
            }
        }
        return this
    }

    fun channels(native: List<String>): List<String> {
        if (reserved.isEmpty()) return native
        if (ids.isEmpty() || nativeIds != native) {
            nativeIds = native.toList()
            ids = nativeIds + reserved
        }
        return ids
    }

    fun selectable(weapon: String): Boolean =
        AircraftStoreWeapons.mountId(weapon) == null && weapon !in hidden

    /** HUD and input share these indices; never construct data for a hidden legacy alias. */
    fun selectableIndices(names: List<String>, available: (String) -> Boolean): List<Int> =
        names.indices.filter { names[it].isNotBlank() && selectable(names[it]) && available(names[it]) }

    fun groups(revision: Long, load: () -> List<AircraftStoreWeapons.Equipped>,
               native: (String) -> Pair<Int, Int>?): List<AircraftStoreWeapons.Group> {
        if (equipment != revision) {
            fitted = AircraftStoreWeapons.collect(load(), native)
            byChannel = buildMap {
                fitted.forEachIndexed { index, group ->
                    put(group.weaponId, index)
                    group.members.forEach { put(AircraftStoreWeapons.PREFIX + it.mountId, index) }
                }
            }
            equipment = revision
        }
        // Virtual missiles have no native GunData feeds. Their immutable list is returned as-is.
        var updated: MutableList<AircraftStoreWeapons.Group>? = null
        for ((index, group) in fitted.withIndex()) {
            if (group.virtual) continue
            var capacity = 0
            var ammo = 0
            for (channel in group.nativeWeapons) native(channel)?.let {
                capacity += it.first
                ammo += it.second
            }
            if (capacity != group.capacity || ammo != group.ammo) {
                if (updated == null) updated = fitted.toMutableList()
                updated[index] = group.copy(capacity = capacity, ammo = ammo)
            }
        }
        if (updated != null) fitted = updated
        return fitted
    }

    fun group(channel: String): AircraftStoreWeapons.Group? = byChannel[channel]?.let(fitted::get)
}
