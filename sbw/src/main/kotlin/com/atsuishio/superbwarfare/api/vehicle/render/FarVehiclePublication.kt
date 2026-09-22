package com.atsuishio.superbwarfare.api.vehicle.render

import java.util.UUID

/** Subscription membership owns removal; a missing capture or terrain receipt does not. */
internal class FarVehiclePublication<T>(private val identity: (T) -> UUID) {
    private val retained = LinkedHashMap<UUID, T>()

    fun reconcile(selected: Set<UUID>, updates: Iterable<T>): List<T> {
        retained.keys.retainAll(selected)
        for (value in updates) {
            val id = identity(value)
            if (id in selected) retained[id] = value
        }
        return selected.mapNotNull(retained::get)
    }
}
