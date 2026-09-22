package com.atsuishio.superbwarfare.api.vehicle.weapon

import net.minecraft.resources.ResourceLocation
import kotlin.math.roundToInt

data class VehicleWeaponScheduleSnapshot(
    val profileId: ResourceLocation,
    val seatIndex: Int,
    val weaponIndex: Int,
    val triggerHeld: Boolean,
    val visualStage: Int,
    val currentRpm: Int,
    val maxRpm: Int,
    val shotCredits: Double,
    val heatFraction: Double,
    val overheatTicks: Int,
    val acceptedSequence: Long,
    val lastDecision: String,
)

object VehicleWeaponScheduleSnapshots {
    @JvmStatic
    fun encode(snapshots: Collection<VehicleWeaponScheduleSnapshot>): String {
        if (snapshots.isEmpty()) return ""
        return buildString(snapshots.size * 96) {
            var first = true
            for (snapshot in snapshots) {
                if (first) first = false else append(';')
                append(snapshot.profileId).append(',')
                append(snapshot.seatIndex).append(',')
                append(snapshot.weaponIndex).append(',')
                append(if (snapshot.triggerHeld) 1 else 0).append(',')
                append(snapshot.visualStage).append(',')
                append(snapshot.currentRpm).append(',')
                append(snapshot.maxRpm).append(',')
                append((snapshot.shotCredits * 1000.0).roundToInt()).append(',')
                append((snapshot.heatFraction * 1000.0).roundToInt()).append(',')
                append(snapshot.overheatTicks).append(',')
                append(snapshot.acceptedSequence).append(',')
                append(snapshot.lastDecision.replace(',', '_').replace(';', '_'))
            }
        }
    }

    @JvmStatic
    fun decode(payload: String?): List<VehicleWeaponScheduleSnapshot> {
        if (payload.isNullOrBlank()) return emptyList()
        return payload.split(';').mapNotNull(::decodeEntry)
    }

    private fun decodeEntry(entry: String): VehicleWeaponScheduleSnapshot? {
        val values = entry.split(',', limit = 12)
        if (values.size != 12) return null
        val profileId = ResourceLocation.tryParse(values[0]) ?: return null
        return runCatching {
            VehicleWeaponScheduleSnapshot(
                profileId = profileId,
                seatIndex = values[1].toInt(),
                weaponIndex = values[2].toInt(),
                triggerHeld = values[3] == "1",
                visualStage = values[4].toInt(),
                currentRpm = values[5].toInt(),
                maxRpm = values[6].toInt(),
                shotCredits = values[7].toInt() / 1000.0,
                heatFraction = values[8].toInt() / 1000.0,
                overheatTicks = values[9].toInt(),
                acceptedSequence = values[10].toLong(),
                lastDecision = values[11],
            )
        }.getOrNull()
    }
}
