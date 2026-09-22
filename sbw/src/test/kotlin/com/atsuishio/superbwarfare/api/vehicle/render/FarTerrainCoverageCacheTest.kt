package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainCoverageCacheTest {
    private val camera = Vec3(0.0, 80.0, 0.0)
    private val box = AABB(250.0, 64.0, 0.0, 260.0, 70.0, 4.0)

    @Test fun `reuses exact geometry without hiding camera or vehicle movement`() {
        val cache = FarTerrainCoverageCache()
        val first = cache.get(camera, box, -4, 20)
        assertSame(first, cache.get(Vec3(0.0, 80.0, 0.0), box, -4, 20))
        val moved = camera.add(0.001, 0.0, 0.0)
        assertNotSame(first, cache.get(moved, box, -4, 20))
        assertEquals(FarTerrainVisibility.coverage(moved, box, -4, 20), cache.get(moved, box, -4, 20))
        assertNotSame(first, cache.get(camera, box.move(0.0, 1.0, 0.0), -4, 20))
        assertNotSame(first, cache.get(camera, box, -4, 21))
    }

    @Test fun `evicts by both entry count and cell weight and clears world state`() {
        val weight = FarTerrainVisibility.coverage(camera, box, -4, 20).let { it.chunks.size + it.sections.size }
        val cache = FarTerrainCoverageCache(2, weight * 2)
        val first = cache.get(camera, box, -4, 20)
        for (i in 1..12) {
            cache.get(camera.add(i.toDouble(), 0.0, 0.0), box, -4, 20)
            assertTrue(cache.cells <= weight * 2)
        }
        assertNotSame(first, cache.get(camera, box, -4, 20))
        cache.clear()
        assertEquals(0, cache.cells)
        assertNotSame(first, cache.get(camera, box, -4, 20))
    }

    @Test fun `oversized footprint remains a rejected sentinel instead of empty valid coverage`() {
        val cache = FarTerrainCoverageCache(maxCells = 10)
        val far = AABB(1.0e7, 60.0, 1.0e7, 1.0e7 + 5.0, 70.0, 1.0e7 + 5.0)
        val coverage = cache.get(camera, far, -4, 20)
        assertEquals(FarTerrainPolicy.MAX_CHUNKS + 1, coverage.chunks.size)
        assertTrue(coverage.sections.isEmpty())
        assertEquals(0, cache.cells)
    }
}
