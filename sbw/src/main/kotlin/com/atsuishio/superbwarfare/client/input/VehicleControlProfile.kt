package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.misc.MonitorItem
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import net.minecraft.world.entity.player.Player

/** Selects mappings for the same recipient priority used by the existing movement handler. */
enum class VehicleControlProfile {
    LAND, PLANE, HELICOPTER, DRONE;

    companion object {
        @JvmStatic
        fun resolve(player: Player): VehicleControlProfile? {
            if (!player.isAlive || player.isSpectator) return null
            val vehicle = player.vehicle as? VehicleEntity
            if (vehicle != null && vehicle.firstPassenger === player) {
                if (vehicle.isRemoved || vehicle.isWreck) return null
                return classify(vehicle.vehicleType, vehicle.isFixedWingFlightVehicle())
            }
            return if (controlsLinkedDrone(player)) DRONE else null
        }

        /** Boats retain their existing controls; the watercraft heading defines no new profile. */
        @JvmStatic
        fun classify(type: VehicleType?, fixedWing: Boolean): VehicleControlProfile = when {
            fixedWing || type == VehicleType.AIRPLANE -> PLANE
            type == VehicleType.HELICOPTER -> HELICOPTER
            type == VehicleType.DRONE -> DRONE
            else -> LAND
        }

        @JvmStatic
        fun controlsLinkedDrone(player: Player): Boolean {
            val stack = player.mainHandItem
            if (!stack.`is`(ModItems.MONITOR.get())) return false
            val tag = stack.tag ?: return false
            if (!tag.getBoolean(MonitorItem.USING) || !tag.getBoolean(MonitorItem.LINKED)) return false
            val drone = EntityFindUtil.findDrone(player.level(), tag.getString("LinkedDrone")) ?: return false
            return !drone.isRemoved && !drone.isWreck
        }
    }
}
