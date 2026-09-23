package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Compatibility API: aircraft remain whole in flight and after impact. */
@Suppress("UNUSED_PARAMETER")
object AircraftWreckBreakup {
    const val LEFT = 1
    const val RIGHT = 2
    @JvmStatic fun detachedAt(vehicle: VehicleEntity, point: Vec3): Boolean = false
    @JvmStatic fun mask(id: UUID): Int = 0
    @JvmStatic fun mask(vehicle: VehicleEntity): Int = 0
    @JvmStatic fun delayTicks(id: UUID): Int = 0
    fun update(vehicle: VehicleEntity) {}
    fun detach(vehicle: VehicleEntity, sides: Int, momentum: Vec3 = vehicle.deltaMovement) {}
}
