package com.atsuishio.superbwarfare.api.aircraft

/** One selectable identity per fitted munition type; physical feeds remain independent. */
internal object AircraftWeaponGroups {
    data class Mount(val id: String, val storeId: String, val category: String, val channels: List<String>,
                     val storeName: String = storeId)
    data class Group(val representative: String, val storeId: String, val category: String, val storeName: String,
                     val members: List<String>, val mounts: List<String>)

    fun podAlias(storeId: String): String = if (storeId == "berts_vehicle_pack:gsh23_pod")
        AircraftGunPodGroups.GROUP else "${AircraftGunPodGroups.GROUP}:$storeId"

    fun groups(mounts: List<Mount>): List<Group> = mounts.groupBy { it.storeId }.values.mapNotNull { sameStore ->
        val first = sameStore.first()
        if (sameStore.any { it.category != first.category }) return@mapNotNull null
        val members = when (first.category) {
            "ROCKET_POD", "GUN_POD" -> sameStore.flatMap { it.channels }.distinct()
            "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "LASER_GUIDED", "CRUISE", "BOMB" ->
                sameStore.map { AircraftStoreWeapons.PREFIX + it.id }
            else -> emptyList()
        }
        if (members.isEmpty()) return@mapNotNull null
        val representative = if (first.category == "GUN_POD") podAlias(first.storeId) else members.first()
        Group(representative, first.storeId, first.category, first.storeName,
            members, sameStore.map { it.id })
    }

    /** Keep the accepted-shot cursor still when every physical launcher is empty or blocked. */
    fun orderedLiveMounts(mounts: List<String>, cursor: Int, remaining: (String) -> Int): List<String> {
        if (mounts.isEmpty()) return emptyList()
        val start = Math.floorMod(cursor, mounts.size)
        return mounts.indices.map { mounts[(start + it) % mounts.size] }
            .filter { remaining(it) > 0 }
    }
}
