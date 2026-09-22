package com.atsuishio.superbwarfare.api.vehicle.action

import net.minecraft.resources.ResourceLocation
import java.util.UUID

/** Immutable server truth consumed by addon HUDs and diagnostics. */
data class VehicleActionSnapshot(
    val actionId: ResourceLocation,
    val sequence: Int,
    val serverTick: Long,
    val operatorId: UUID,
    val phaseId: ResourceLocation,
    val phaseTicks: Int,
    val targetId: ResourceLocation?,
    val inputHeld: Boolean,
) {
    fun encode(): String = buildString(128) {
        append(actionId).append(',')
        append(sequence).append(',')
        append(serverTick).append(',')
        append(operatorId).append(',')
        append(phaseId).append(',')
        append(phaseTicks.coerceAtLeast(0)).append(',')
        append(targetId?.toString().orEmpty()).append(',')
        append(if (inputHeld) 1 else 0)
    }

    companion object {
        @JvmStatic
        fun decode(payload: String): VehicleActionSnapshot? {
            val fields = payload.split(',', limit = 8)
            if (fields.size != 8) return null
            return runCatching {
                VehicleActionSnapshot(
                    ResourceLocation(fields[0]),
                    fields[1].toInt(),
                    fields[2].toLong(),
                    UUID.fromString(fields[3]),
                    ResourceLocation(fields[4]),
                    fields[5].toInt(),
                    fields[6].takeIf(String::isNotEmpty)?.let { ResourceLocation(it) },
                    fields[7] == "1",
                )
            }.getOrNull()?.takeIf { it.phaseTicks >= 0 }
        }
    }
}

object VehicleActionSnapshots {
    @JvmStatic
    fun encode(snapshots: Collection<VehicleActionSnapshot>): String {
        if (snapshots.size < 2) return snapshots.firstOrNull()?.encode().orEmpty()
        return snapshots.sortedBy { it.actionId.toString() }.joinToString("|") { it.encode() }
    }

    @JvmStatic
    fun decode(payload: String?): List<VehicleActionSnapshot> {
        if (payload.isNullOrBlank()) return emptyList()
        return payload.split('|').mapNotNull(VehicleActionSnapshot::decode)
    }
}
