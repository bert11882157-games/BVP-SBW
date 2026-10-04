package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingGearSupport
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingTerrainPrisms
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.chunk.ChunkStatus
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import kotlin.math.max
import kotlin.math.min

/** Loaded-world wheel query. Missing chunks and exhausted budgets never authorize a correction. */
internal class FixedWingGearSupportProbe(private val vehicle: VehicleEntity) {
    data class Result(val height: Double?, val complete: Boolean, val cells: Int)

    fun sample(gear: List<OBB>, movement: Vec3, rotationLift: Double): Result {
        val level = vehicle.level() as? ServerLevel ?: return Result(null, false, 0)
        if (gear.isEmpty() || gear.size > 32 || movement.lengthSqr() > 64.0) return Result(null, false, 0)
        val terrain = ArrayList<AABB>()
        val visited = HashSet<Long>()
        val position = BlockPos.MutableBlockPos()
        val context = CollisionContext.of(vehicle)
        for (wheel in gear) {
            val bounds = FixedWingContactSweep.Body(wheel).bounds
                .move(movement.x, 0.0, movement.z)
                .expandTowards(0.0, rotationLift + FixedWingGearSupport.CONTACT_EPSILON, 0.0)
                .expandTowards(0.0, min(movement.y, 0.0) - FixedWingGearSupport.CONTACT_EPSILON, 0.0)
                .inflate(1.0)
            val minX = Mth.floor(bounds.minX); val maxX = Mth.floor(bounds.maxX)
            val minY = max(level.minBuildHeight, Mth.floor(bounds.minY))
            val maxY = min(level.maxBuildHeight - 1, Mth.floor(bounds.maxY))
            val minZ = Mth.floor(bounds.minZ); val maxZ = Mth.floor(bounds.maxZ)
            if ((maxX.toLong() - minX + 1) * (maxY.toLong() - minY + 1) *
                (maxZ.toLong() - minZ + 1) > 8192) return Result(null, false, visited.size)
            // Carrier decks are terrain for the wheels too.
            for (deck in com.atsuishio.superbwarfare.api.vehicle.deck.DeckCollisions.boxes(level, bounds, vehicle)) {
                if (terrain.size >= 2048) return Result(null, false, visited.size)
                terrain.add(deck)
            }
            for (x in minX..maxX) for (z in minZ..maxZ) {
                val chunk = level.chunkSource.getChunk(x shr 4, z shr 4, ChunkStatus.FULL, false)
                    ?: return Result(null, false, visited.size)
                for (y in minY..maxY) {
                    position.set(x, y, z)
                    if (!visited.add(position.asLong())) continue
                    if (visited.size > 8192) return Result(null, false, visited.size)
                    val state = chunk.getBlockState(position)
                    if (state.isAir) continue
                    for (box in state.getCollisionShape(level, position, context).toAabbs()) {
                        if (terrain.size >= 2048) return Result(null, false, visited.size)
                        terrain.add(box.move(x.toDouble(), y.toDouble(), z.toDouble()))
                    }
                }
            }
        }
        return Result(FixedWingGearSupport.supportHeight(gear, FixedWingTerrainPrisms.compact(terrain),
            movement, rotationLift), true, visited.size)
    }
}
