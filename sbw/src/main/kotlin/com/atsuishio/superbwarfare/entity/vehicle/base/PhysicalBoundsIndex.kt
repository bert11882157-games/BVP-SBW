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
    // Primitive long keys: query probes one cell per section of every Level.getEntities box.
    private val sections = it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<MutableSet<Entry<T>>>()
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
        else for (cell in cells) {
            val key: Long = cell
            (sections.get(key) ?: HashSet<Entry<T>>().also { sections.put(key, it) }).add(entry)
        }
    }

    fun remove(owner: T) {
        val entry = entries.remove(owner) ?: return
        if (entry.cells == null) oversized.remove(entry)
        else for (cell in entry.cells) {
            val key: Long = cell
            sections.get(key)?.let { occupants ->
                occupants.remove(entry)
                if (occupants.isEmpty()) sections.remove(key)
            }
        }
    }

    /**
     * Owners whose envelope intersects [bounds], in the order oversized entries then section cells (x, y, z). This
     * runs inside every Level.getEntities call, so it allocates nothing when the index is empty or nothing is hit.
     */
    fun query(bounds: AABB): List<T> {
        if (entries.isEmpty()) return emptyList()
        val minX = Mth.floor(bounds.minX) shr 4; val maxX = Mth.floor(bounds.maxX) shr 4
        val minY = Mth.floor(bounds.minY) shr 4; val maxY = Mth.floor(bounds.maxY) shr 4
        val minZ = Mth.floor(bounds.minZ) shr 4; val maxZ = Mth.floor(bounds.maxZ) shr 4
        val nx = maxX.toLong() - minX + 1; val ny = maxY.toLong() - minY + 1; val nz = maxZ.toLong() - minZ + 1
        var out: ArrayList<T>? = null
        var seen: HashSet<Entry<T>>? = null
        fun offer(entry: Entry<T>) {
            val owner = entry.owner.get() ?: return
            if (!entry.bounds.intersects(bounds)) return
            val unique = seen ?: HashSet<Entry<T>>().also { seen = it }
            if (unique.add(entry)) (out ?: ArrayList<T>().also { out = it }).add(owner)
        }
        if (nx > 4096 || ny > 4096 || nz > 4096 || nx * ny * nz > 4096L) {
            // Oversized query bounds use the aircraft-only entry set, never a world scan.
            for (entry in entries.values.toList()) offer(entry)
        } else {
            for (entry in oversized) offer(entry)
            for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ)
                sections.get(SectionPos.asLong(x, y, z))?.let { cell -> for (entry in cell) offer(entry) }
        }
        return out ?: emptyList()
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
