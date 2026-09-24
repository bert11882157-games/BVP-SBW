package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Advisory impact point for the selected bomb. Samples loaded terrain without loading chunks. */
object AircraftBombPredictor {
    /** Match the server's copy-aware rack slot for the next accepted bomb release. */
    internal fun nextLaunchPosition(mount: JsonObject, store: JsonObject,
                                    totalCapacity: Int, remaining: Int): Vec3? {
        val perRack = AircraftArmamentRegistry.mountCapacity(mount, store["Capacity"]?.asInt ?: 1)
        if (totalCapacity <= 0 || totalCapacity % perRack != 0) return null
        val copies = totalCapacity / perRack
        if (copies !in 1..(if (mount["Internal"]?.asBoolean == true)
            AircraftPylonRacks.MAX_BAY_COPIES else AircraftPylonRacks.MAX_COPIES)) return null
        // A client snapshot can briefly pair a new count with an old fired value.
        // An invalid interval has no advisory impact point; it is never reindexed.
        if (remaining !in 1..totalCapacity) return null
        val fired = totalCapacity - remaining
        val offset = AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO
        return AircraftPylonRacks.launchPosition(mount, store, copies, fired).add(offset)
    }

    @JvmStatic fun predictImpact(vehicle: VehicleEntity, weaponId: String): Vec3? {
        val key = AircraftStoreWeapons.mountId(weaponId) ?: return null
        val def = AircraftArmamentManager.definition(vehicle) ?: return null
        val mount = AircraftArmamentRegistry.mounts(def).firstOrNull { it["Id"].asString == key } ?: return null
        val store = AircraftArmamentManager.equippedStore(vehicle, key) ?: return null
        if (store["Category"]?.asString != "BOMB") return null
        val bomb = store.getAsJsonObject("Bomb") ?: return null
        val totalCapacity = AircraftArmamentManager.mountCapacity(vehicle, key)
        val remaining = AircraftArmamentManager.mountRemaining(vehicle, key)
        if (remaining <= 0) return null
        val neutral = nextLaunchPosition(mount, store, totalCapacity, remaining) ?: return null
        val local = AircraftMountSweep.position(mount,
            (totalCapacity - remaining) % AircraftArmamentRegistry.mountPositions(mount).size,
            neutral, vehicle.getVehicleFlightPresentationSnapshot(1f).motion.length())
        var point = AircraftBombFlight.launchOrigin(vehicle, local)
        var motion = AircraftBombFlight.initialMotion(vehicle.deltaMovement)
        val gravity = bomb["Gravity"].asDouble
        val drag = bomb["DragMultiplier"].asDouble
        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite() ||
            !motion.x.isFinite() || !motion.y.isFinite() || !motion.z.isFinite()) return null
        val level = vehicle.level()
        for (tick in 0 until 600) {
            val next = point.add(motion)
            if (!level.hasChunkAt(net.minecraft.core.BlockPos.containing(next))) return null
            val hit = level.clip(ClipContext(point, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, vehicle))
            if (hit.type == HitResult.Type.BLOCK) return hit.location
            point = next
            if (point.y < level.minBuildHeight) return null
            motion = AircraftBombFlight.afterPredictedAirStep(motion, gravity, drag)
        }
        return null
    }
}
