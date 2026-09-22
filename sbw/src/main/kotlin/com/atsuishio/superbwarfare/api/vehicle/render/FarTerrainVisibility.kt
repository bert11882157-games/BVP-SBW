package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.floor

/** Conservative section coverage of every sightline from the camera to a model envelope. */
object FarTerrainVisibility {
    data class Section(val chunk: Long, val y: Int) {
        // Packed chunk coordinates hash to x xor z with Long.hashCode. On diagonal
        // terrain this collapses thousands of cells into the same few hash buckets.
        override fun hashCode(): Int = it.unimi.dsi.fastutil.HashCommon.long2int(
            it.unimi.dsi.fastutil.HashCommon.mix(chunk)) * 31 + y
    }

    @JvmStatic
    fun sections(camera: Vec3, bounds: AABB, minSection: Int, maxSection: Int): Set<Section> =
        coverage(camera, bounds, minSection, maxSection).sections

    data class Coverage(val chunks: Set<Long>, val sections: Set<Section>)

    fun coverage(camera: Vec3, bounds: AABB, minSection: Int, maxSection: Int): Coverage {
        val box = bounds.inflate(FarTerrainPolicy.RENDER_PADDING_BLOCKS)
        val result = LinkedHashSet<Section>()
        val footprint = FarTerrainPolicy.requiredCoverage(camera.x, camera.z,
            box.minX, box.minZ, box.maxX, box.maxZ)
        if (footprint.size > FarTerrainPolicy.MAX_CHUNKS) return Coverage(footprint, result)
        for (key in footprint) {
            var low = 0.0
            var high = 1.0
            // At fraction t, the pyramid occupies [camera+t*(min-camera), camera+t*(max-camera)].
            // Intersect that interval with the chunk column on both horizontal axes.
            fun atLeast(coefficient: Double, value: Double): Boolean {
                if (kotlin.math.abs(coefficient) < 1.0e-12) return value <= 0.0
                val t = value / coefficient
                if (coefficient > 0) low = maxOf(low, t) else high = minOf(high, t)
                return low <= high
            }
            val x = FarTerrainPolicy.x(key) * 16.0
            val z = FarTerrainPolicy.z(key) * 16.0
            val epsilon = 1.0e-6
            if (!atLeast(box.maxX - camera.x, x - epsilon - camera.x) ||
                !atLeast(camera.x - box.minX, camera.x - (x + 16 + epsilon)) ||
                !atLeast(box.maxZ - camera.z, z - epsilon - camera.z) ||
                !atLeast(camera.z - box.minZ, camera.z - (z + 16 + epsilon))) continue
            val heights = doubleArrayOf(camera.y + low * (box.minY - camera.y),
                camera.y + high * (box.minY - camera.y),
                camera.y + low * (box.maxY - camera.y), camera.y + high * (box.maxY - camera.y))
            // Neighbor blocks may emit faces beyond their cell; include a block on either side.
            val bottom = maxOf(minSection, floor((heights.min() - 1.0) / 16.0).toInt())
            val top = minOf(maxSection - 1, floor((heights.max() + 1.0) / 16.0).toInt())
            for (y in bottom..top) result.add(Section(key, y))
        }
        return Coverage(footprint, result)
    }
}
