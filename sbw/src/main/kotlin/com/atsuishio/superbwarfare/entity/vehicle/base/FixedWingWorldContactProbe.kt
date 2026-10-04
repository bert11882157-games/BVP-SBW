package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSurface
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingGearSupport
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingTerrainPrisms
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.max
import kotlin.math.min

/** Bounded loaded-section query. Exhaustion or missing support ownership is unknown, never clear. */
internal class FixedWingWorldContactProbe(private val vehicle: VehicleEntity) {
    data class Result(
        val contact: FixedWingContactSweep.Contact?, val landingGear: Boolean,
        val complete: Boolean, val cells: Int, val emptySections: Int,
        val bodyOverlap: Boolean = false,
    )

    fun sample(
        movement: Vec3, originOffset: Vec3, initialSupportFloor: Double? = null,
        previousBodies: List<FixedWingContactSweep.Body>? = null,
        terrainBoxes: List<OBBInfo>? = null,
    ): Result {
        val level = vehicle.level() as? ServerLevel ?: return Result(null, false, false, 0, 0)
        val sources = terrainBoxes ?: vehicle.obb
        // Old anonymous proxies cannot distinguish wheel support from a wing/fuselage scrape.
        if (sources.isEmpty() || sources.size > MAX_BOXES ||
            (terrainBoxes == null && sources.none { it.landingGear })) {
            return Result(null, false, false, 0, 0)
        }
        if (terrainBoxes == null) vehicle.updateOBB()
        var cells = 0; var emptySections = 0; var sectionVisits = 0; var shapeTests = 0
        var sweepTests = 0
        // Partitioned bodies revisit the same floor voxels. Cache their immutable shapes for
        // this query only, with separate bounds on unique neighbors and actual body sweeps.
        val collisionBoxes = HashMap<Long, List<AABB>>()
        var complete = true
        var best: FixedWingContactSweep.Contact? = null
        var bestGear = false
        var bestPriority = -1.0
        var bodyOverlap = false
        val block = BlockPos.MutableBlockPos()
        val context = CollisionContext.of(vehicle)
        val surfaceBudget = FixedWingContactSurface.Budget()
        for ((index, info) in sources.withIndex()) {
            if (terrainBoxes == null && info.landingGear && vehicle.hasFixedWingLandingGear() && vehicle.synchedGearRot == 1f) continue
            val body = try { FixedWingContactSweep.Body(info.getOBB().move(originOffset)) }
                catch (_: IllegalArgumentException) { complete = false; continue }
            val exactBounds = body.bounds.expandTowards(movement).inflate(1e-7)
            val terrain = ArrayList<AABB>()
            var terrainComplete = true
            // Fences and moving blocks can protrude beyond their own cell.
            val swept = exactBounds.inflate(1.0)
            if (swept.xsize > 128 || swept.ysize > 128 || swept.zsize > 128) {
                complete = false; continue
            }
            val minX = Mth.floor(swept.minX); val maxX = Mth.floor(swept.maxX)
            val minY = max(level.minBuildHeight, Mth.floor(swept.minY))
            val maxY = min(level.maxBuildHeight - 1, Mth.floor(swept.maxY))
            val minZ = Mth.floor(swept.minZ); val maxZ = Mth.floor(swept.maxZ)
            if (minY > maxY) continue
            for (chunkX in (minX shr 4)..(maxX shr 4)) for (chunkZ in (minZ shr 4)..(maxZ shr 4)) {
                val chunk = level.chunkSource.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false)
                if (chunk == null) { complete = false; terrainComplete = false; continue }
                for (sectionY in (minY shr 4)..(maxY shr 4)) {
                    if (++sectionVisits > MAX_SECTIONS) return Result(best, bestGear, false, cells, emptySections, bodyOverlap)
                    val section = chunk.getSection(sectionY - level.minSection)
                    if (section.hasOnlyAir()) { emptySections++; continue }
                    val firstX = max(minX, chunkX shl 4); val lastX = min(maxX, (chunkX shl 4) + 15)
                    val firstY = max(minY, sectionY shl 4); val lastY = min(maxY, (sectionY shl 4) + 15)
                    val firstZ = max(minZ, chunkZ shl 4); val lastZ = min(maxZ, (chunkZ shl 4) + 15)
                    for (x in firstX..lastX) for (y in firstY..lastY) for (z in firstZ..lastZ) {
                        if (++cells > MAX_CELLS) return Result(best, bestGear, false, cells - 1, emptySections, bodyOverlap)
                        block.set(x, y, z)
                        val state = section.getBlockState(x and 15, y and 15, z and 15)
                        if (state.isAir) continue
                        val key = block.asLong()
                        val boxes = collisionBoxes[key] ?: run {
                            val loaded = ArrayList<AABB>()
                            for (local in state.getCollisionShape(level, block, context).toAabbs()) {
                                if (++shapeTests > MAX_SHAPES)
                                    return Result(best, bestGear, false, cells, emptySections, bodyOverlap)
                                loaded.add(local.move(x.toDouble(), y.toDouble(), z.toDouble()))
                            }
                            if (loaded.isNotEmpty()) collisionBoxes[key] = loaded
                            loaded
                        }
                        for (obstacle in boxes) {
                            if (!swept.intersects(obstacle)) continue
                            terrain.add(obstacle)
                        }
                    }
                }
            }
            // Carrier decks are terrain: their columns join the voxels (never full voxels, so kept as boxes).
            for (deck in com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions.boxes(level, swept, vehicle)) {
                if (swept.intersects(deck)) terrain.add(deck)
            }
            // Full adjacent voxels are the same solid as their exact rectangular union. Remove
            // internal voxel faces before the bounded sweep and exposed-surface work.
            val compactTerrain = FixedWingTerrainPrisms.compact(terrain)
            for (obstacle in compactTerrain) {
                if (!exactBounds.intersects(obstacle)) continue
                if (++sweepTests > MAX_SHAPES)
                    return Result(best, bestGear, false, cells, emptySections, bodyOverlap)
                val raw = body.sweep(movement, obstacle) ?: continue
                // A newly rotated box can start below the already-supported wheel plane. Its
                // reference-point rise resolves that initial floor overlap. Keep every later
                // contact on the actual diagonal path, including raised ledges and overhangs.
                if (FixedWingGearSupport.rotationCreatedFloorOverlap(body, previousBodies?.getOrNull(index),
                    raw, movement, obstacle, initialSupportFloor)) continue
                if (!info.landingGear && raw.initiallyOverlapping) bodyOverlap = true
                // Missing occlusion neighbors cannot certify an exposed face in this body query.
                if (!terrainComplete) continue
                val contact = if (terrainBoxes != null) {
                    val sample = AircraftTerrainContactQuery.resolve(body, info.getOBB(), info.landingGear,
                        movement, obstacle, raw, compactTerrain, surfaceBudget)
                    if (!sample.complete) complete = false
                    sample.contact ?: continue
                } else {
                    val surface = FixedWingContactSurface.resolve(body, movement, obstacle, raw,
                        compactTerrain, surfaceBudget)
                    if (!surface.complete) complete = false
                    raw.copy(normal = surface.normal ?: continue)
                }
                val normal = contact.normal
                val normalSpeed = -movement.dot(normal)
                val priority = if (normalSpeed >= 0.3) 2.0 + normalSpeed else
                    (if (info.landingGear) 0.0 else 1.0) + normalSpeed.coerceIn(0.0, 0.3)
                val preferred = if (terrainBoxes == null) priority > bestPriority else
                    AircraftTerrainContactQuery.preferred(contact, info.landingGear, best, bestGear)
                if (best == null || preferred) {
                    best = contact; bestGear = info.landingGear; bestPriority = priority
                }
            }
        }
        return Result(best, bestGear, complete, cells, emptySections, bodyOverlap)
    }

    private companion object {
        const val MAX_BOXES = 64
        const val MAX_CELLS = 32768
        const val MAX_SECTIONS = 4096
        const val MAX_SHAPES = 2048
    }
}
