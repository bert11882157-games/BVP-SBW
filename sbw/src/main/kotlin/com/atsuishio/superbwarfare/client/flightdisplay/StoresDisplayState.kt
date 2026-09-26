package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * One frame of the stores management page: the airframe outline and every pylon position with what hangs on it.
 * Positions are vehicle-local blocks (+X left, +Z forward), like the planform lines.
 */
data class StoresDisplayState(
    val planform: Array<DoubleArray>,
    val stations: List<Station>,
    val guns: List<String>,
    val payloadKg: Double,
    val live: Boolean,
) {
    data class Station(val x: Double, val z: Double, val mount: String, val store: String?, val category: String?,
                       val remaining: Int, val loaded: Boolean, val internal: Boolean, val selected: Boolean)

    companion object {
        @JvmField
        val STILL = StoresDisplayState(emptyArray(), emptyList(), emptyList(), 0.0, false)

        @JvmStatic
        fun sample(vehicle: VehicleEntity, planform: Array<DoubleArray>?): StoresDisplayState {
            val snap = AircraftArmamentClient.getVehicleSnapshot(vehicle)
                ?: return StoresDisplayState(planform ?: emptyArray(), emptyList(), emptyList(), 0.0, true)
            val selectedStore = snap.seek?.weaponId?.takeIf { it.isNotBlank() }
            val stations = ArrayList<Station>()
            for (mount in snap.definition.mounts) {
                val store = snap.stores[snap.selections[mount.id]]
                val copies = if (store == null) 0 else (snap.counts[mount.id] ?: store.fixedRackCount ?: 1)
                val perPosition = if (store == null) 0 else (store.capacity ?: 1) * copies
                val fired = snap.fired[mount.id] ?: 0
                for ((index, p) in mount.positions.withIndex()) {
                    // Launches alternate across positions: position k has lost ceil((fired - k) / positions).
                    val gone = if (fired > index) (fired - index + mount.positions.size - 1) / mount.positions.size else 0
                    val consumable = store != null && store.category in setOf("LASER_GUIDED", "COMMAND_GUIDED", "BOMB",
                        "CRUISE", "AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION")
                    val remaining = if (store == null) 0 else if (consumable) (perPosition - gone).coerceAtLeast(0) else perPosition
                    stations.add(Station(p.x, p.z, mount.name, store?.name, store?.category, remaining,
                        store != null && (!consumable || remaining > 0), mount.internal,
                        store != null && selectedStore != null && store.id == selectedStore))
                }
            }
            return StoresDisplayState(planform ?: emptyArray(), stations, snap.definition.builtIn, snap.payloadKg(), true)
        }
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
