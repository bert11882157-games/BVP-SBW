package com.atsuishio.superbwarfare.api.aircraft
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/** Authored armaments support ground and air vehicles; seat 0 is the operating station. */
object AircraftTerminalAccess {
    private fun vehicle(player: Player, entity: Entity): VehicleEntity? {
        val vehicle = entity as? VehicleEntity ?: return null
        return vehicle.takeIf { player.vehicle === it && it.getSeatIndex(player) == 0 &&
            player.isAlive && !player.isSpectator && it.isAlive && !it.isWreck && it.health > 0 }
    }
    @JvmStatic fun radarRange(player: Player, entity: Entity): Int {
        val vehicle = vehicle(player, entity) ?: return 0
        val radar = AircraftArmamentManager.definition(vehicle)?.getAsJsonObject("Radar") ?: return 0
        if (radar.get("Enabled")?.asBoolean != true) return 0
        return (radar.get("Range")?.asInt ?: 500).takeIf { it in 16..4096 } ?: 0
    }

    /** Optional FFA ABI. Each entry is one equipped coordinate weapon, identified by mount ID. */
    @JvmStatic fun coordinateWeapons(player: Player, entity: Entity): CompoundTag {
        val result = CompoundTag()
        val vehicle = vehicle(player, entity) ?: return result
        val definition = AircraftArmamentManager.definition(vehicle) ?: return result
        val selected = listOfNotNull(vehicle.getGunName(0),
            vehicle.getSecondaryWeaponIndex(0)?.let { vehicle.getGunName(0, it) })
        val slots = ListTag()
        for (mount in AircraftArmamentRegistry.mounts(definition)) {
            val id = mount["Id"].asString
            val store = AircraftArmamentManager.equippedStore(vehicle, id) ?: continue
            val profile = AircraftCoordinateLauncher.profile(store) ?: continue
            val storeId = AircraftArmamentManager.equippedStoreId(vehicle, id)
            if (storeId.isNullOrEmpty()) continue
            slots.add(CompoundTag().apply {
                putString("Id", id)
                putString("Name", AircraftArmamentRegistry.storeWeaponName(mount, store).take(128))
                putString("Store", storeId)
                putString("Profile", profile)
                putBoolean("CruiseOnly", store["Category"].asString == "CRUISE_MISSILE")
                putBoolean("Selected", AircraftStoreWeapons.PREFIX + id in selected)
            })
        }
        result.put("Slots", slots)
        return result
    }
}
