package com.atsuishio.superbwarfare.api.vehicle.render

import java.util.UUID

/** A bounded presentation hint; it never grants vehicle membership or client-chosen chunks. */
internal data class FarTerrainView(val vehicles: Set<UUID>, val offsetX: Double, val offsetZ: Double) {
    companion object {
        const val MAX_CAMERA_OFFSET = 128.0

        fun parse(vehicles: List<String>, x: Double, z: Double): FarTerrainView? {
            if (vehicles.size > FarVehicleStore.MAX_VEHICLES || !x.isFinite() || !z.isFinite() ||
                x * x + z * z > MAX_CAMERA_OFFSET * MAX_CAMERA_OFFSET + 0.0001) return null
            val ids = linkedSetOf<UUID>()
            for (value in vehicles) {
                val id = runCatching { UUID.fromString(value) }.getOrNull() ?: return null
                if (id.toString() != value || !ids.add(id)) return null
            }
            return FarTerrainView(ids, x, z)
        }
    }
}
