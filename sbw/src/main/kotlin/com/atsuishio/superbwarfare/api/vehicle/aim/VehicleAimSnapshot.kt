package com.atsuishio.superbwarfare.api.vehicle.aim

/** Immutable server truth consumed by renderers and HUDs. */
data class VehicleAimSnapshot(
    val sequence: Int,
    val serverTick: Long,
    val channel: VehicleAimChannel,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val mode: VehicleAimMode,
    val targetYaw: Float,
    val targetPitch: Float,
    val actualYaw: Float,
    val actualPitch: Float,
    val locked: Boolean,
) {
    fun encode(): String = buildString(96) {
        append(sequence).append(',')
        append(serverTick).append(',')
        append(channel.name).append(',')
        append(seatIndex).append(',')
        append(selectedWeaponIndex).append(',')
        append(mode.name).append(',')
        append(targetYaw).append(',')
        append(targetPitch).append(',')
        append(actualYaw).append(',')
        append(actualPitch).append(',')
        append(if (locked) 1 else 0)
    }

    companion object {
        @JvmStatic
        fun decode(payload: String): VehicleAimSnapshot? {
            val f = payload.split(',', limit = 11)
            if (f.size != 11) return null
            return runCatching {
                VehicleAimSnapshot(
                    f[0].toInt(),
                    f[1].toLong(),
                    VehicleAimChannel.valueOf(f[2]),
                    f[3].toInt(),
                    f[4].toInt(),
                    VehicleAimMode.valueOf(f[5]),
                    f[6].toFloat(),
                    f[7].toFloat(),
                    f[8].toFloat(),
                    f[9].toFloat(),
                    f[10] == "1",
                )
            }.getOrNull()?.takeIf {
                it.seatIndex >= 0 &&
                        it.targetYaw.isFinite() && it.targetPitch.isFinite() &&
                        it.actualYaw.isFinite() && it.actualPitch.isFinite()
            }
        }
    }
}

object VehicleAimSnapshots {
    @JvmStatic
    fun encode(snapshots: Collection<VehicleAimSnapshot>): String {
        if (snapshots.size < 2) return snapshots.firstOrNull()?.encode().orEmpty()
        return snapshots.sortedBy { it.channel.ordinal }.joinToString("|") { it.encode() }
    }

    @JvmStatic
    fun decode(payload: String?): List<VehicleAimSnapshot> {
        return decodeStrict(payload).orEmpty()
    }

    /**
     * Decodes one complete entity-data payload without turning a malformed member into an
     * apparent channel omission. The client uses null to preserve its current atomic channel
     * state; a genuinely blank payload remains the explicit lifecycle clear.
     */
    @JvmStatic
    fun decodeStrict(payload: String?): List<VehicleAimSnapshot>? {
        if (payload.isNullOrBlank()) return emptyList()
        val fields = payload.split('|')
        val decoded = ArrayList<VehicleAimSnapshot>(fields.size)
        for (field in fields) {
            val snapshot = VehicleAimSnapshot.decode(field) ?: return null
            decoded += snapshot
        }
        return decoded
    }
}
