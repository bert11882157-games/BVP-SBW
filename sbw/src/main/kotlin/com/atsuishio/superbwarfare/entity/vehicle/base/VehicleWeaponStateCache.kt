package com.atsuishio.superbwarfare.entity.vehicle.base

/**
 * Owns detached live weapon state and its authoritative snapshot/configuration identities.
 * Failed reconstruction leaves the previous binding intact; publication advances only the
 * snapshot identity, retaining live state until an external snapshot or configuration replaces it.
 */
internal class VehicleWeaponStateCache<T : Any> {
    private var snapshotOwner: Map<String, T>? = null
    private var configurationOwner: Any? = null
    private var live: Map<String, T> = emptyMap()

    fun resolve(snapshot: Map<String, T>, configuration: Any, materialize: () -> Map<String, T>): Map<String, T> {
        if (snapshotOwner === snapshot && configurationOwner === configuration) return live
        val next = materialize()
        snapshotOwner = snapshot
        configurationOwner = configuration
        live = next
        return next
    }

    fun published(snapshot: Map<String, T>) {
        snapshotOwner = snapshot
    }

    fun invalidateConfiguration() {
        configurationOwner = null
    }

    fun clear() {
        snapshotOwner = null
        configurationOwner = null
        live = emptyMap()
    }
}
