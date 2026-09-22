package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.AABB
import kotlin.math.floor

/** Exact union-preserving compaction of full collision voxels; partial shapes stay separate. */
internal object FixedWingTerrainPrisms {
    fun compact(terrain: List<AABB>): List<AABB> {
        if (terrain.size < 2) return terrain
        val partial = ArrayList<AABB>()
        var solids = ArrayList<AABB>()
        for (box in terrain) {
            if (box.xsize == 1.0 && box.ysize == 1.0 && box.zsize == 1.0 &&
                box.minX == floor(box.minX) && box.minY == floor(box.minY) && box.minZ == floor(box.minZ)) {
                solids.add(box)
            } else partial.add(box)
        }
        // Each pass joins only touching intervals with exactly equal extents on both other
        // axes. Three bounded sorts suffice for rectangular terrain without filling any gap.
        for (axis in intArrayOf(2, 0, 1)) {
            val u = (axis + 1) % 3
            val v = (axis + 2) % 3
            solids.sortWith(compareBy<AABB>({ lower(it, u) }, { upper(it, u) },
                { lower(it, v) }, { upper(it, v) }, { lower(it, axis) }, { upper(it, axis) }))
            val joined = ArrayList<AABB>(solids.size)
            for (box in solids) {
                val previous = joined.lastOrNull()
                if (previous != null && lower(previous, u) == lower(box, u) &&
                    upper(previous, u) == upper(box, u) && lower(previous, v) == lower(box, v) &&
                    upper(previous, v) == upper(box, v) && upper(previous, axis) == lower(box, axis)) {
                    joined[joined.lastIndex] = previous.minmax(box)
                } else joined.add(box)
            }
            solids = joined
        }
        partial.addAll(solids)
        return partial
    }

    private fun lower(box: AABB, axis: Int) = when (axis) { 0 -> box.minX; 1 -> box.minY; else -> box.minZ }
    private fun upper(box: AABB, axis: Int) = when (axis) { 0 -> box.maxX; 1 -> box.maxY; else -> box.maxZ }
}
