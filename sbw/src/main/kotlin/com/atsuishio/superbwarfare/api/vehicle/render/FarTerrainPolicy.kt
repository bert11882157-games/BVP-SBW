package com.atsuishio.superbwarfare.api.vehicle.render

import kotlin.math.floor
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet

/** Shared limits and horizontal geometry; the server chooses every subscribed chunk. */
object FarTerrainPolicy {
    const val MAX_CHUNKS = 2048
    const val GLOBAL_CHUNKS = 8192
    const val MIN_ACQUISITION_RADIUS = 160
    const val MAX_ACQUISITION_RADIUS = 2560
    const val REQUEST_TTL = 100L
    const val MAX_CHUNK_BYTES = 1024 * 1024 - 4096
    const val DISCOVERY_PER_TICK = 8
    const val RENDER_PADDING_BLOCKS = 16.0

    /** Keep an admitted corridor through small distance reversals without starving nearer arrivals. */
    fun selectionDistance(distanceSquared: Double, retained: Boolean): Double =
        (kotlin.math.sqrt(distanceSquared) - if (retained) 16.0 else 0.0).coerceAtLeast(0.0)

    fun radius(clientChunks: Int, serverChunks: Int): Int =
        minOf(clientChunks.coerceIn(2, 32), serverChunks.coerceIn(2, 32)) * 5 * 16

    fun inside(px: Double, pz: Double, x: Double, z: Double, radius: Int): Boolean =
        x.isFinite() && z.isFinite() && (x - px) * (x - px) + (z - pz) * (z - pz) <= radius.toDouble() * radius

    fun deferNative(distanceSquared: Double, viewChunks: Int, halfWidth: Double, enabled: Boolean, ridden: Boolean): Boolean {
        if (!enabled || ridden) return false
        val safe = ((viewChunks.coerceAtLeast(2) - 1) * 16.0 - halfWidth.coerceAtLeast(0.0)).coerceAtLeast(0.0)
        return distanceSquared > safe * safe
    }

    fun key(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or (z.toLong() shl 32)
    fun x(key: Long): Int = key.toInt()
    fun z(key: Long): Int = (key shr 32).toInt()
    fun chunk(value: Double): Int = floor(value / 16.0).toInt()

    /** Includes a chunk of prefetch margin on either side of the intervening terrain. */
    fun corridor(px: Double, pz: Double, x: Double, z: Double): Set<Long> =
        coverage(px, pz, x, z, x, z)

    fun boundedUnion(current: Set<Long>, addition: Set<Long>, limit: Int = MAX_CHUNKS): Set<Long>? {
        if (addition.count { it !in current } + current.size > limit) return null
        return LongLinkedOpenHashSet(current).also { it.addAll(addition) }
    }

    /** Subscription includes spare terrain for movement; the spare margin never gates visibility. */
    fun coverage(px: Double, pz: Double, minX: Double, minZ: Double,
                 maxX: Double, maxZ: Double): Set<Long> {
        val result = LongLinkedOpenHashSet()
        for (required in renderCoverage(px, pz, minX, minZ, maxX, maxZ)) {
            for (dx in -1..1) for (dz in -1..1) {
                result.add(key(x(required) + dx, z(required) + dz))
                if (result.size > MAX_CHUNKS) return result
            }
        }
        return result
    }

    /** Match the far render pass's conservative model envelope, including protruding geometry. */
    fun renderCoverage(px: Double, pz: Double, minX: Double, minZ: Double,
                       maxX: Double, maxZ: Double): Set<Long> = requiredCoverage(px, pz,
        minX - RENDER_PADDING_BLOCKS, minZ - RENDER_PADDING_BLOCKS,
        maxX + RENDER_PADDING_BLOCKS, maxZ + RENDER_PADDING_BLOCKS)

    /**
     * Rasterizes the convex footprint between the observer and the entire vehicle bounds.
     * Every hull edge is among the point pairs. Their intersections with each chunk row give
     * its full horizontal span, including interior cover and cells touched at a grid corner.
     * An over-budget result contains MAX_CHUNKS + 1 cells and cannot pass the coverage gate.
     * Retained vehicles can be world-spanning distances away; geometry work must stay bounded.
     */
    fun requiredCoverage(px: Double, pz: Double, minX: Double, minZ: Double,
                         maxX: Double, maxZ: Double): Set<Long> {
        val xs = doubleArrayOf(px, minX, minX, maxX, maxX)
        val zs = doubleArrayOf(pz, minZ, maxZ, minZ, maxZ)
        val epsilon = 1.0e-7
        val result = LongLinkedOpenHashSet()
        for (cz in chunk(zs.min() - epsilon)..chunk(zs.max() + epsilon)) {
            val lowZ = cz * 16.0 - epsilon
            val highZ = (cz + 1) * 16.0 + epsilon
            var lowX = Double.POSITIVE_INFINITY
            var highX = Double.NEGATIVE_INFINITY
            for (i in xs.indices) {
                if (zs[i] in lowZ..highZ) {
                    lowX = minOf(lowX, xs[i]); highX = maxOf(highX, xs[i])
                }
                for (j in i + 1 until xs.size) {
                    if (zs[i] == zs[j]) continue
                    for (edge in 0..1) {
                        val t = ((if (edge == 0) lowZ else highZ) - zs[i]) / (zs[j] - zs[i])
                        if (t !in 0.0..1.0) continue
                        val atX = xs[i] + t * (xs[j] - xs[i])
                        lowX = minOf(lowX, atX); highX = maxOf(highX, atX)
                    }
                }
            }
            if (lowX <= highX) {
                for (cx in chunk(lowX - epsilon)..chunk(highX + epsilon)) {
                    result.add(key(cx, cz))
                    if (result.size > MAX_CHUNKS) return result
                }
            }
        }
        return result
    }
}
