package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.core.SectionPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.AABB
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Physical envelopes indexed in every occupied section, independent of an entity's origin cell. */
internal class PhysicalBoundsIndex<T : Any> {
    private class Entry<T : Any>(owner: T, var bounds: AABB, val cells: Set<Long>?) {
        val owner = WeakReference(owner)
    }
    private val entries = WeakHashMap<T, Entry<T>>()
    private val sections = HashMap<Long, MutableSet<Entry<T>>>()
    private val oversized = HashSet<Entry<T>>()

    fun update(owner: T, bounds: AABB) {
        val cells = cells(bounds)
        val old = entries[owner]
        if (old != null && old.cells == cells) {
            old.bounds = bounds
            return
        }
        remove(owner)
        val entry = Entry(owner, bounds, cells)
        entries[owner] = entry
        if (cells == null) oversized.add(entry)
        else for (cell in cells) sections.getOrPut(cell) { HashSet() }.add(entry)
    }

    fun remove(owner: T) {
        val entry = entries.remove(owner) ?: return
        if (entry.cells == null) oversized.remove(entry)
        else for (cell in entry.cells) {
            sections[cell]?.let { occupants ->
                occupants.remove(entry)
                if (occupants.isEmpty()) sections.remove(cell)
            }
        }
    }

    fun query(bounds: AABB): List<T> {
        val cells = cells(bounds)
        val candidates = if (cells == null) entries.values.toSet() else buildSet {
            addAll(oversized)
            for (cell in cells) sections[cell]?.let { addAll(it) }
        }
        return candidates.mapNotNull { entry -> entry.owner.get()?.takeIf { entry.bounds.intersects(bounds) } }
    }

    private fun cells(bounds: AABB): Set<Long>? {
        val minX = Mth.floor(bounds.minX) shr 4; val maxX = Mth.floor(bounds.maxX) shr 4
        val minY = Mth.floor(bounds.minY) shr 4; val maxY = Mth.floor(bounds.maxY) shr 4
        val minZ = Mth.floor(bounds.minZ) shr 4; val maxZ = Mth.floor(bounds.maxZ) shr 4
        val nx = maxX.toLong() - minX + 1; val ny = maxY.toLong() - minY + 1; val nz = maxZ.toLong() - minZ + 1
        if (nx > 4096 || ny > 4096 || nz > 4096) return null
        val count = nx * ny * nz
        // Oversized authored/query bounds use the aircraft-only entry set, never a world scan.
        if (count > 4096L) return null
        return buildSet {
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ)
                add(SectionPos.asLong(x, y, z))
        }
    }
}
