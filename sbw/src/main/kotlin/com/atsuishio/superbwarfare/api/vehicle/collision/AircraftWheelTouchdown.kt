package com.atsuishio.superbwarfare.api.vehicle.collision

import net.minecraft.world.phys.Vec3
import java.util.Collections

@kotlinx.serialization.Serializable
enum class AircraftWheelContactGroup { MAIN, NOSE, TAIL }

/** One accepted server contact edge; positions are actual world-space tyre support points. */
class AircraftWheelTouchdown(
    val sequence: Long,
    val serverTick: Long,
    val group: AircraftWheelContactGroup,
    contacts: List<Vec3>,
    val sinkSpeedBlocksPerTick: Double,
) {
    val contacts: List<Vec3> = Collections.unmodifiableList(contacts.toList())

    init {
        require(sequence > 0 && serverTick >= 0)
        require(contacts.isNotEmpty() && contacts.size <= 32 && contacts.all {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        })
        require(sinkSpeedBlocksPerTick.isFinite() && sinkSpeedBlocksPerTick > 0.0)
    }
}
