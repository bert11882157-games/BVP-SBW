package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainResidencyTest {
    @Test fun `completed cover releases server chunks while offscreen vehicles remain resident`() {
        val vehicles = setOf(10L, 20L)
        val pending = (100L..2147L).toSet()
        val first = FarTerrainResidency.select(vehicles, pending, emptySet(), 64)
        assertEquals(66, first.size)
        val finished = first - vehicles
        val next = FarTerrainResidency.select(vehicles, pending - finished, first, 64)
        assertTrue(next.containsAll(vehicles))
        assertTrue(next.intersect(finished).isEmpty())
        assertEquals(66, next.size)
        assertEquals(vehicles, FarTerrainResidency.select(vehicles, emptySet(), next, 64))
    }

    @Test fun `in flight loads survive priority changes and do not consume target slots twice`() {
        val required = setOf(1L, 2L, 3L)
        val queued = linkedSetOf(90L, 91L, 92L, 1L, 20L, 21L)
        val result = FarTerrainResidency.select(required, queued, setOf(1L, 20L, 21L, 999L), 3)
        assertEquals(setOf(1L, 2L, 3L, 20L, 21L, 90L), result)
        assertEquals(required, FarTerrainResidency.select(required, queued, result, 0))
    }

    @Test fun `projectile path remains pinned independently of completed visual delivery`() {
        val vehicles = setOf(1L)
        val projectilePath = setOf(100L, 101L, 102L)
        val held = vehicles + projectilePath
        assertEquals(held, FarTerrainResidency.select(held, emptySet(), held, 64))
        assertEquals(vehicles, FarTerrainResidency.select(vehicles, emptySet(), held, 64))
    }
}
