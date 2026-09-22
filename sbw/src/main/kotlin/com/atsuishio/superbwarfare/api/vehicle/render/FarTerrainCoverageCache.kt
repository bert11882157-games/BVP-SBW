package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Exact geometry reuse only: movement never reuses a smaller or differently positioned corridor. */
internal class FarTerrainCoverageCache(
    private val maxEntries: Int = 256,
    private val maxCells: Int = 65536,
) {
    private data class Query(val camera: Vec3, val bounds: AABB, val min: Int, val max: Int)
    private val entries = LinkedHashMap<Query, FarTerrainVisibility.Coverage>(16, 0.75f, true)
    var cells = 0
        private set

    fun get(camera: Vec3, bounds: AABB, min: Int, max: Int): FarTerrainVisibility.Coverage {
        val key = Query(camera, bounds, min, max)
        entries[key]?.let { return it }
        val value = FarTerrainVisibility.coverage(camera, bounds, min, max)
        val weight = value.chunks.size + value.sections.size
        if (weight > maxCells || maxEntries <= 0) return value
        val iterator = entries.entries.iterator()
        while ((entries.size >= maxEntries || cells + weight > maxCells) && iterator.hasNext()) {
            val old = iterator.next().value
            cells -= old.chunks.size + old.sections.size
            iterator.remove()
        }
        entries[key] = value
        cells += weight
        return value
    }

    fun clear() { entries.clear(); cells = 0 }
}
