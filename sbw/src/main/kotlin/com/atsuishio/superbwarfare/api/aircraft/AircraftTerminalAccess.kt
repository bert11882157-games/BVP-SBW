package com.atsuishio.superbwarfare.api.aircraft
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/** Same authored radar capability on both sides; seat0 is the aircraft operating station. */
object AircraftTerminalAccess {
    @JvmStatic fun canOpen(player: Player, entity: Entity): Boolean {
        val vehicle = entity as? VehicleEntity ?: return false
        if (player.vehicle !== vehicle || vehicle.getSeatIndex(player) != 0 || vehicle.isWreck || vehicle.health <= 0) return false
        if (radarRange(player, entity) > 0) return true
        return gpsCapable(entity)
    }
    @JvmStatic fun gpsCapable(entity: Entity): Boolean {
        val vehicle = entity as? VehicleEntity ?: return false
        val definition = AircraftArmamentManager.definition(vehicle) ?: return false
        return AircraftArmamentRegistry.mounts(definition).any { mount ->
            val store = AircraftArmamentManager.equippedStore(vehicle, mount["Id"].asString)
            store?.getAsJsonObject("Bomb")?.get("Mode")?.asString == "GPS" ||
                store?.get("Category")?.asString == "CRUISE"
        }
    }
    @JvmStatic fun radarRange(player: Player, entity: Entity): Int {
        val vehicle = entity as? VehicleEntity ?: return 0
        if (player.vehicle !== vehicle || vehicle.getSeatIndex(player) != 0 || vehicle.isWreck || vehicle.health <= 0) return 0
        val radar = AircraftArmamentManager.definition(vehicle)?.getAsJsonObject("Radar") ?: return 0
        if (radar.get("Enabled")?.asBoolean != true) return 0
        return (radar.get("Range")?.asInt ?: 500).takeIf { it in 16..4096 } ?: 0
    }
}
