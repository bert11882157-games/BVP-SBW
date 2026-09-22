package com.atsuishio.superbwarfare.api.vehicle.render

import java.util.UUID

/** Distance admits new vehicles; only authoritative removal or session teardown retires them. */
internal object FarVehicleSubscription {
    fun reconcile(previous: Set<UUID>, existing: Set<UUID>, nearby: List<UUID>, limit: Int): Set<UUID> {
        val result = previous.filterTo(linkedSetOf()) { it in existing }
        for (id in nearby) {
            if (result.size >= limit) break
            if (id in existing) result.add(id)
        }
        return result
    }
}
