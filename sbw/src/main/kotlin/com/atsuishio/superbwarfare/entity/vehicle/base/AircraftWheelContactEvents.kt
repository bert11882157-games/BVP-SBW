package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelTouchdown
import net.minecraft.world.phys.Vec3

/** Independent wheel-group contact edges; presentation never owns support or movement. */
internal class AircraftWheelContactEvents {
    private val gates = AircraftWheelContactGroup.entries.associateWith { AircraftGearImpactGate(1e-5) }
    private var sequence = 0L

    fun sample(tick: Long, complete: Boolean, contacts: Map<AircraftWheelContactGroup, List<Vec3>>,
               speeds: Map<AircraftWheelContactGroup, Double>): List<AircraftWheelTouchdown> =
        AircraftWheelContactGroup.entries.mapNotNull { group ->
            val points = contacts[group].orEmpty()
            val speed = gates.getValue(group).sample(tick, complete, points.isNotEmpty(), speeds[group] ?: 0.0)
            if (speed <= 0.0) null else AircraftWheelTouchdown(++sequence, tick, group, points, speed)
        }
}
