package com.atsuishio.superbwarfare.api.vehicle.presentation

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity

/**
 * Crew sitting inside a ground vehicle's hull (tank, IFV, APC, boat, closed car) do not get explosion or hit
 * camera shake: blasts outside, ERA bricks going off and penetrations. Their own vehicle's gun still shakes them.
 * Aircraft and helicopter seats keep every shake, and so do exposed seats (open hatch, roof gun).
 */
object EnclosedCrewShake {
    @JvmStatic
    fun shields(type: VehicleType?, enclosedSeat: Boolean): Boolean =
        enclosedSeat && type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER

    @JvmStatic
    fun shields(player: Entity): Boolean {
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        return shields(vehicle.vehicleType, vehicle.isEnclosed(player))
    }

    /** True when [player] still gets a shake that [source] sends, e.g. its own vehicle's gun firing. */
    @JvmStatic
    fun receives(player: Entity, source: Entity?): Boolean =
        (source != null && player.vehicle === source) || !shields(player)
}
