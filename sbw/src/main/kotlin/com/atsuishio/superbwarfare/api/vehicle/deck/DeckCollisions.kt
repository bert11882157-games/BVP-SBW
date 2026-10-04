package com.atsuishio.superbwarfare.api.vehicle.deck

import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Deck columns as world collision geometry. A query box is sampled on a world-aligned grid (0.25 block, coarser
 * only for very large probe boxes); each sample takes the column of the deck cell under it, and equal neighbouring
 * columns are merged into as few axis-aligned boxes as possible (a query over the flat flight deck is one box).
 * The grid is world-aligned so every query sees the same boxes, exactly as block shapes do.
 */
object DeckCollisions {
    private const val RESOLUTION = 0.25
    private const val MAX_CELLS = 16384
    private const val EPS = 1e-7

    /** A deck never collides with its owner, nor with the owner's passengers (seats place them). */
    @JvmStatic
    fun ignores(owner: Entity, entity: Entity?): Boolean =
        entity != null && (entity === owner || entity.rootVehicle === owner)

    /** World boxes of every deck column that may meet [query]. Empty when there is no deck nearby. */
    @JvmStatic
    fun boxes(level: Level, query: AABB, entity: Entity?): List<AABB> {
        val owners = DeckRegistry.providers(level)
        if (owners.isEmpty()) return emptyList()
        var out: ArrayList<AABB>? = null
        for (owner in owners) {
            if (owner.isRemoved || ignores(owner, entity)) continue
            val surface = (owner as DeckSurfaceEntity).deckSurface() ?: continue
            val pose = DeckPose.of(owner)
            if (!mayMeet(surface, pose, query)) continue
            if (out == null) out = ArrayList(4)
            rasterize(surface, pose, query, out)
        }
        return out ?: emptyList()
    }

    @JvmStatic
    fun shapes(level: Level, query: AABB, entity: Entity?): List<VoxelShape> {
        val boxes = boxes(level, query, entity)
        if (boxes.isEmpty()) return emptyList()
        return boxes.map { Shapes.create(it) }
    }

    /** True when any deck column meets [box]. */
    @JvmStatic
    fun intersects(level: Level, box: AABB, entity: Entity?): Boolean =
        boxes(level, box, entity).any { it.intersects(box) }

    internal fun mayMeet(surface: DeckSurface, pose: DeckPose, query: AABB): Boolean {
        if (query.maxY < pose.y + surface.minBottom - EPS || query.minY > pose.y + surface.maxTop + EPS) return false
        val cx = (query.minX + query.maxX) * 0.5 - pose.x
        val cz = (query.minZ + query.maxZ) * 0.5 - pose.z
        val reach = surface.radius + 0.5 * sqrt(query.xsize * query.xsize + query.zsize * query.zsize) + 1.0
        return cx * cx + cz * cz <= reach * reach
    }

    /** Appends the merged column boxes of one deck within [query] to [out]. */
    @JvmStatic
    fun rasterize(surface: DeckSurface, pose: DeckPose, query: AABB, out: MutableList<AABB>) {
        var res = RESOLUTION
        var ix0: Long; var ix1: Long; var iz0: Long; var iz1: Long
        while (true) {
            ix0 = floor(query.minX / res).toLong(); ix1 = floor(query.maxX / res).toLong()
            iz0 = floor(query.minZ / res).toLong(); iz1 = floor(query.maxZ / res).toLong()
            if ((ix1 - ix0 + 1) * (iz1 - iz0 + 1) <= MAX_CELLS) break
            res *= 2.0
        }
        val nx = (ix1 - ix0 + 1).toInt()
        val nz = (iz1 - iz0 + 1).toInt()
        // runs of the previous row still open: start, end (inclusive, cell offsets), column, first row
        var openStart = IntArray(8); var openEnd = IntArray(8); var openTop = DoubleArray(8)
        var openBottom = DoubleArray(8); var openRow = IntArray(8); var openCount = 0
        var curStart = IntArray(8); var curEnd = IntArray(8); var curTop = DoubleArray(8)
        var curBottom = DoubleArray(8); var curRow = IntArray(8)
        val minY = query.minY - EPS
        val maxY = query.maxY + EPS

        fun emit(start: Int, end: Int, top: Double, bottom: Double, row0: Int, row1: Int) {
            val y1 = pose.y + top
            val y0 = pose.y + bottom
            if (y1 < minY || y0 > maxY) return
            out.add(AABB((ix0 + start) * res, y0, (iz0 + row0) * res, (ix0 + end + 1) * res, y1, (iz0 + row1 + 1) * res))
        }

        for (row in 0..nz) {
            var curCount = 0
            if (row < nz) {
                val wz = (iz0 + row + 0.5) * res
                var runStart = -1; var runTop = 0.0; var runBottom = 0.0
                for (col in 0..nx) {
                    var k = -1
                    if (col < nx) {
                        val wx = (ix0 + col + 0.5) * res
                        k = surface.column(pose.localX(wx, wz), pose.localZ(wx, wz))
                    }
                    val top = if (k >= 0) surface.topOf(k) else Double.NaN
                    val bottom = if (k >= 0) surface.bottomOf(k) else Double.NaN
                    if (runStart >= 0 && (k < 0 || top != runTop || bottom != runBottom)) {
                        if (curCount == curStart.size) {
                            curStart = curStart.copyOf(curCount * 2); curEnd = curEnd.copyOf(curCount * 2)
                            curTop = curTop.copyOf(curCount * 2); curBottom = curBottom.copyOf(curCount * 2)
                            curRow = curRow.copyOf(curCount * 2)
                        }
                        curStart[curCount] = runStart; curEnd[curCount] = col - 1
                        curTop[curCount] = runTop; curBottom[curCount] = runBottom; curRow[curCount] = row
                        curCount++
                        runStart = -1
                    }
                    if (k >= 0 && runStart < 0) { runStart = col; runTop = top; runBottom = bottom }
                }
            }
            // carry identical runs down from the previous row, close the rest
            var p = 0
            for (c in 0 until curCount) {
                while (p < openCount && openStart[p] < curStart[c]) {
                    emit(openStart[p], openEnd[p], openTop[p], openBottom[p], openRow[p], row - 1); p++
                }
                if (p < openCount && openStart[p] == curStart[c] && openEnd[p] == curEnd[c] &&
                    openTop[p] == curTop[c] && openBottom[p] == curBottom[c]) {
                    curRow[c] = openRow[p]; p++
                }
            }
            while (p < openCount) {
                emit(openStart[p], openEnd[p], openTop[p], openBottom[p], openRow[p], row - 1); p++
            }
            val ts = openStart; openStart = curStart; curStart = ts
            val te = openEnd; openEnd = curEnd; curEnd = te
            val tt = openTop; openTop = curTop; curTop = tt
            val tb = openBottom; openBottom = curBottom; curBottom = tb
            val tr = openRow; openRow = curRow; curRow = tr
            openCount = curCount
        }
    }

    /**
     * The OBBs of a deck owner a player's cursor may pick: those standing more than a block above the flight
     * deck (the island). Hull boxes end at the deck surface, so picking them would turn every click on the deck
     * into a click on the ship.
     */
    @JvmStatic
    fun pickableObbs(owner: Entity, obbs: List<com.atsuishio.superbwarfare.tools.OBB>):
        MutableList<com.atsuishio.superbwarfare.tools.OBB> {
        val surface = (owner as? DeckSurfaceEntity)?.deckSurface() ?: return obbs.toMutableList()
        val deck = owner.y + surface.deckHeight + 1.0
        return obbs.filterTo(ArrayList()) { it.center.y + it.extents.y > deck }
    }

    /**
     * World height of the highest deck column top at or below [y] over (x, z), no more than [maxDepth] below it;
     * NaN when there is none.
     */
    @JvmStatic
    fun surfaceBelow(level: Level, x: Double, y: Double, z: Double, maxDepth: Double, entity: Entity?): Double {
        val owners = DeckRegistry.providers(level)
        if (owners.isEmpty()) return Double.NaN
        var best = Double.NaN
        for (owner in owners) {
            if (owner.isRemoved || ignores(owner, entity)) continue
            val surface = (owner as DeckSurfaceEntity).deckSurface() ?: continue
            val pose = DeckPose.of(owner)
            val k = surface.column(pose.localX(x, z), pose.localZ(x, z))
            if (k < 0) continue
            val top = pose.y + surface.topOf(k)
            if (top <= y + EPS && y - top <= maxDepth && (best.isNaN() || top > best)) best = top
        }
        return best
    }

    class Hit(@JvmField val location: Vec3, @JvmField val owner: Entity, @JvmField val fromAbove: Boolean)

    /** First point where the segment enters a deck column, or null. */
    @JvmStatic
    fun clip(level: Level, from: Vec3, to: Vec3, entity: Entity?): Hit? {
        val owners = DeckRegistry.providers(level)
        if (owners.isEmpty()) return null
        var best: Hit? = null
        var bestDistance = Double.MAX_VALUE
        for (owner in owners) {
            if (owner.isRemoved || ignores(owner, entity)) continue
            val surface = (owner as DeckSurfaceEntity).deckSurface() ?: continue
            val entry = clip(surface, DeckPose.of(owner), from, to) ?: continue
            val d = entry.first.distanceToSqr(from)
            if (d < bestDistance) { bestDistance = d; best = Hit(entry.first, owner, entry.second) }
        }
        return best
    }

    /** Where the segment enters one deck's columns, and whether through a column top; null when it misses. */
    @JvmStatic
    fun clip(surface: DeckSurface, pose: DeckPose, from: Vec3, to: Vec3): Pair<Vec3, Boolean>? {
        val length = from.distanceTo(to)
        if (length < 1e-9) return null
        val envelope = pose.envelope(surface)
        if (!envelope.intersects(AABB(from, to).inflate(EPS))) return null
        // march only through the part of the segment inside the envelope
        var t0 = 0.0
        var t1 = 1.0
        if (!envelope.contains(from)) {
            val entry = envelope.clip(from, to).orElse(null) ?: return null
            t0 = entry.distanceTo(from) / length
        }
        if (!envelope.contains(to)) {
            val exit = envelope.clip(to, from).orElse(null)
            if (exit != null) t1 = 1.0 - exit.distanceTo(to) / length
        }
        fun inside(p: Vec3): Int {
            val k = surface.column(pose.localX(p.x, p.z), pose.localZ(p.x, p.z))
            return if (k >= 0 && p.y <= pose.y + surface.topOf(k) + EPS && p.y >= pose.y + surface.bottomOf(k) - EPS) k
            else -1
        }
        val steps = ((t1 - t0) * length / 0.05).toInt().coerceIn(1, 8000)
        var prevT = t0
        var prevK = -1
        var prevAbove = true
        for (s in 0..steps) {
            val t = t0 + (t1 - t0) * s / steps
            val p = lerp(from, to, t)
            val k = inside(p)
            if (k >= 0) {
                if (s > 0 && prevK == k && prevAbove) {
                    // crossed this column's top plane
                    val topY = pose.y + surface.topOf(k)
                    val a = lerp(from, to, prevT)
                    val f = if (abs(a.y - p.y) < 1e-12) 1.0 else ((a.y - topY) / (a.y - p.y)).coerceIn(0.0, 1.0)
                    return Vec3(a.x + (p.x - a.x) * f, topY, a.z + (p.z - a.z) * f) to true
                }
                // refine the entry between the previous sample and this one
                var lo = prevT
                var hi = t
                repeat(16) {
                    val mid = (lo + hi) * 0.5
                    if (inside(lerp(from, to, mid)) >= 0) hi = mid else lo = mid
                }
                val e = lerp(from, to, hi)
                val ke = surface.column(pose.localX(e.x, e.z), pose.localZ(e.x, e.z))
                val topY = if (ke >= 0) pose.y + surface.topOf(ke) else e.y
                val fromAbove = ke >= 0 && abs(e.y - topY) < 0.05
                return (if (fromAbove) Vec3(e.x, topY, e.z) else e) to fromAbove
            }
            val kc = surface.column(pose.localX(p.x, p.z), pose.localZ(p.x, p.z))
            prevAbove = kc >= 0 && p.y > pose.y + surface.topOf(kc)
            prevK = kc
            prevT = t
        }
        return null
    }

    private fun lerp(a: Vec3, b: Vec3, t: Double) = Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

    /**
     * Sneaking on a deck: vanilla's edge back-off only sees blocks, so over open water it would freeze every step.
     * Returns the movement backed off from deck and block edges alike, or null when no deck supports [entity].
     */
    @JvmStatic
    fun backOffFromEdge(entity: Entity, movement: Vec3, stepDown: Double): Vec3? {
        val level = entity.level()
        val box = entity.boundingBox
        val support = box.move(0.0, -stepDown, 0.0)
        if (!intersects(level, support, entity)) return null
        fun free(dx: Double, dz: Double): Boolean {
            val moved = box.move(dx, -stepDown, dz)
            return level.noCollision(entity, moved) && !intersects(level, moved, entity)
        }
        val step = 0.05
        var dx = movement.x
        var dz = movement.z
        while (dx != 0.0 && free(dx, 0.0)) dx = if (dx < step && dx >= -step) 0.0 else if (dx > 0.0) dx - step else dx + step
        while (dz != 0.0 && free(0.0, dz)) dz = if (dz < step && dz >= -step) 0.0 else if (dz > 0.0) dz - step else dz + step
        while (dx != 0.0 && dz != 0.0 && free(dx, dz)) {
            dx = if (dx < step && dx >= -step) 0.0 else if (dx > 0.0) dx - step else dx + step
            dz = if (dz < step && dz >= -step) 0.0 else if (dz > 0.0) dz - step else dz + step
        }
        return Vec3(dx, movement.y, dz)
    }

    /** Highest deck top under any of [entity]'s footprint samples whose top lies within [below, above] of its feet. */
    @JvmStatic
    fun supportTop(entity: Entity, pose: DeckPose, surface: DeckSurface, below: Double, above: Double): Double {
        val box = entity.boundingBox
        val feet = box.minY
        val inset = 0.05
        val xs = doubleArrayOf((box.minX + box.maxX) * 0.5, box.minX + inset, box.maxX - inset)
        val zs = doubleArrayOf((box.minZ + box.maxZ) * 0.5, box.minZ + inset, box.maxZ - inset)
        var best = Double.NaN
        for (x in xs) for (z in zs) {
            val k = surface.column(pose.localX(x, z), pose.localZ(x, z))
            if (k < 0) continue
            val top = pose.y + surface.topOf(k)
            if (feet >= top - below && feet <= top + above && (best.isNaN() || top > best)) best = top
        }
        return best
    }

}
