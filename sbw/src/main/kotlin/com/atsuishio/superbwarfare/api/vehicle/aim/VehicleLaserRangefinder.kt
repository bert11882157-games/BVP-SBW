package com.atsuishio.superbwarfare.api.vehicle.aim

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemKind
import com.atsuishio.superbwarfare.data.vehicle.WeaponSystemMetadata
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries
import net.minecraft.world.level.ChunkPos
import java.util.concurrent.CompletableFuture

/** One key-edge query; never creates projectiles, terrain tickets, or a persistent target lock. */
object VehicleLaserRangefinder {
    fun enabled(vehicle: VehicleEntity, seat: Int, weapon: Int): Boolean {
        if (vehicle.resolveVehicleFlightStrategy() != null || vehicle.vehicleType == VehicleType.AIRPLANE ||
            vehicle.vehicleType == VehicleType.HELICOPTER) return false
        val name = vehicle.getGunName(seat, weapon) ?: return false
        val gun = vehicle.getGunData(name) ?: return false
        val nominal = gun.getDefault().nominalBallistics
        val combat = nominal?.projectileProfile?.let { CustomData.PROJECTILE_PROFILE[it]?.combat }
        val kind = WeaponSystemMetadata.resolve(ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)?.toString(),
            name, combat?.weaponId?.toString()) { CustomData.WEAPON_SYSTEM_METADATA[it] }
        return vehicle.computed().hasFCS || isCannon(kind, nominal?.projectileType)
    }

    internal fun isCannon(kind: WeaponSystemKind?, projectileType: String?): Boolean =
        if (kind != null) kind == WeaponSystemKind.TANK_CANNON || kind == WeaponSystemKind.AUTOCANNON
        else projectileType == "superbwarfare:cannon_shell" || projectileType == "superbwarfare:small_cannon_shell"

    fun measure(level: ServerLevel, source: VehicleEntity, start: Vec3, direction: Vec3, range: Double): Double? {
        val end = start.add(direction.normalize().scale(range))
        val context = ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)
        val terrain = traceTerrain(start, end) { pos ->
            // Never call getBlockState before checking availability: a long laser must not load chunks.
            if (!level.hasChunkAt(pos)) TerrainProbe(false, null)
            else TerrainProbe(true, context.getBlockShape(level.getBlockState(pos), level, pos)
                .clip(start, end, pos)?.location)
        }
        return nearestVehicle(level, source, start, end, terrain.point, terrain.limitSquared)?.let(start::distanceTo)
    }

    /** Capture loaded geometry on the server thread, then read only missing saved palettes off-thread. */
    fun measureAsync(level: ServerLevel, source: VehicleEntity, start: Vec3, direction: Vec3,
                     range: Double, includeVehicles: Boolean = true): CompletableFuture<Double?> {
        val fullEnd = start.add(direction.normalize().scale(range))
        val vehicleHit = if (includeVehicles) nearestVehicle(level, source, start, fullEnd, null, range * range) else null
        val end = vehicleHit ?: fullEnd
        val context = ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source)
        val missing = linkedMapOf<ChunkPos, MutableList<BlockPos>>()
        val terrain = traceTerrain(start, end) { pos ->
            if (!level.hasChunkAt(pos)) {
                // Out-of-world cells are known air, not missing chunks needing disk I/O.
                if (!level.isOutsideBuildHeight(pos)) missing.getOrPut(ChunkPos(pos)) { mutableListOf() }.add(pos.immutable())
                TerrainProbe(true, null)
            } else TerrainProbe(true, context.getBlockShape(level.getBlockState(pos), level, pos)
                .clip(start, end, pos)?.location)
        }
        val known = terrain.point ?: vehicleHit
        if (missing.isEmpty()) return CompletableFuture.completedFuture(known?.let(start::distanceTo))
        return VehicleLaserSavedTerrain.trace(level, start, end, missing, known)
    }

    private fun nearestVehicle(level: ServerLevel, source: VehicleEntity, start: Vec3, end: Vec3,
                               known: Vec3?, maximumSquared: Double): Vec3? {
        var closest = known
        var distanceSquared = maximumSquared
        // Iterate already-loaded entities once per key edge, avoiding a giant AABB chunk search.
        for (target in level.allEntities) {
            if (target !is VehicleEntity || target === source || target.isRemoved || !target.isAlive) continue
            // Detailed geometry may extend beyond the entity collision box (especially wings).
            if (!target.boundingBox.inflate(64.0).intersects(AABB(start, end))) continue
            val hit = if (target is ProjectileCollisionTarget && target.usesDetailedProjectileCollision()) {
                // A material miss is final, exactly as it is for a projectile.
                target.clipProjectile(start, end)?.point()
            } else if (!target.enableAABB()) {
                ProjectileHitSelection.nearestObb(target.getOBBs(), start, end, 0.0)?.point()
            } else target.boundingBox.clip(start, end).orElse(null)
            if (isNearer(start, hit, distanceSquared)) {
                closest = hit
                distanceSquared = start.distanceToSqr(hit!!)
            }
        }
        return closest
    }

    internal data class TerrainProbe(val loaded: Boolean, val point: Vec3?)
    internal data class TerrainLimit(val point: Vec3?, val limitSquared: Double)

    /** Stop at unknown terrain; it is neither empty space nor a measurable surface. */
    internal fun traceTerrain(start: Vec3, end: Vec3, probe: (BlockPos) -> TerrainProbe): TerrainLimit =
        BlockGetter.traverseBlocks(start, end, probe, { lookup, pos ->
            val cell = lookup(pos)
            when {
                !cell.loaded -> {
                    val boundary = if (AABB(pos).contains(start)) start
                        else AABB(pos).clip(start, end).orElse(start)
                    TerrainLimit(null, start.distanceToSqr(boundary))
                }
                cell.point != null -> TerrainLimit(cell.point, start.distanceToSqr(cell.point))
                else -> null
            }
        }, { TerrainLimit(null, start.distanceToSqr(end)) })

    internal fun isNearer(start: Vec3, point: Vec3?, limitSquared: Double): Boolean {
        val distance = point?.let { start.distanceToSqr(it) } ?: return false
        return distance.isFinite() && distance >= 0.0 && distance < limitSquared
    }
}
