package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Chooses one fitted native rocket channel per shot; each channel retains its own gun lifecycle. */
object AircraftRocketPodOrder {
    private const val LAST_SIDE = "BvpRocketLastSide"
    private const val NEXT_CHANNEL = "BvpRocketNextChannel"
    private const val GROUP_ORDER = "BvpRocketGroupOrder"

    internal data class Candidate(val name: String, val side: Side)
    internal enum class Side { LEFT, RIGHT }

    internal fun ordered(candidates: List<Candidate>, last: Side?, cursor: Int): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val preferred = if (last == Side.LEFT) Side.RIGHT else Side.LEFT
        val start = Math.floorMod(cursor, candidates.size)
        val rotated = candidates.indices.map { candidates[(start + it) % candidates.size] }
        return rotated.filter { it.side == preferred } + rotated.filter { it.side != preferred }
    }

    internal fun candidates(vehicle: VehicleEntity, trigger: String): List<Candidate>? {
        val group = AircraftArmamentManager.groupFor(vehicle, trigger)
            ?.takeIf { it.category == "ROCKET_POD" } ?: return null
        val channels = group.members
        val ready = channels.mapNotNull { name ->
            val data = vehicle.getGunData(name) ?: return@mapNotNull null
            if (data.ammo.get() <= 0) return@mapNotNull null
            val attachment = data.firePositionAttachment() ?: return@mapNotNull null
            val side = when {
                "_left_" in attachment -> Side.LEFT
                "_right_" in attachment -> Side.RIGHT
                else -> return@mapNotNull null
            }
            Candidate(name, side)
        }
        val tag = vehicle.persistentData.getCompound(GROUP_ORDER).getCompound(group.representative)
        val last = when (tag.getString(LAST_SIDE)) {
            "left" -> Side.LEFT
            "right" -> Side.RIGHT
            else -> null
        }
        return ordered(ready, last, tag.getInt(NEXT_CHANNEL))
    }

    internal fun accepted(vehicle: VehicleEntity, channel: String, side: Side) {
        val group = AircraftArmamentManager.groupFor(vehicle, channel)
            ?.takeIf { it.category == "ROCKET_POD" } ?: return
        val channels = group.members
        val index = channels.indexOf(channel)
        if (index < 0) return
        val order = vehicle.persistentData.getCompound(GROUP_ORDER)
        val tag = order.getCompound(group.representative)
        tag.putString(LAST_SIDE, if (side == Side.LEFT) "left" else "right")
        tag.putInt(NEXT_CHANNEL, (index + 1) % channels.size)
        order.put(group.representative, tag)
        vehicle.persistentData.put(GROUP_ORDER, order)
    }

    fun clear(vehicle: VehicleEntity) {
        vehicle.persistentData.remove(LAST_SIDE)
        vehicle.persistentData.remove(NEXT_CHANNEL)
        vehicle.persistentData.remove(GROUP_ORDER)
    }
}
