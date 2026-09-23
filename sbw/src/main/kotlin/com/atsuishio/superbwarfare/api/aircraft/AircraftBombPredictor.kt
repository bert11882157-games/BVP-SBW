package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Advisory impact point for the selected bomb. Samples loaded terrain without loading chunks. */
object AircraftBombPredictor {
    @JvmStatic fun predictImpact(vehicle: VehicleEntity, weaponId: String): Vec3? {
        val key = AircraftStoreWeapons.mountId(weaponId) ?: return null
        val def = AircraftArmamentManager.definition(vehicle) ?: return null
        val mount = AircraftArmamentRegistry.mounts(def).firstOrNull { it["Id"].asString == key } ?: return null
        val store = AircraftArmamentManager.equippedStore(vehicle, key) ?: return null
        if (store["Category"]?.asString != "BOMB" || AircraftArmamentManager.mountRemaining(vehicle, key) <= 0) return null
        val bomb = store.getAsJsonObject("Bomb") ?: return null
        val capacity = AircraftArmamentRegistry.mountCapacity(mount, store["Capacity"]?.asInt ?: 1)
        val used = capacity - AircraftArmamentManager.mountRemaining(vehicle, key)
        val offset = AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO
        val local = AircraftArmamentRegistry.launchPosition(mount, used).add(offset)
        val position = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        var point = Vec3(position.x, position.y, position.z)
        var motion = vehicle.deltaMovement.add(0.0, -0.04, 0.0)
        val gravity = bomb["Gravity"].asDouble
        val horizontal = (1.0 - 0.01 * bomb["DragMultiplier"].asDouble).coerceIn(0.8, 1.0)
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
            motion = NominalProjectileMotion.afterFastThrowableAirStep(motion, gravity)
            motion = Vec3(motion.x * horizontal, motion.y, motion.z * horizontal)
        }
        return null
    }
}
