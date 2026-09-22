package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarProjectileReadinessTest {
    @Test fun `a thousand bullets sharing a corridor probe each chunk only once`() {
        val cache = FarProjectileReadiness<Int>(32)
        cache.begin(1, 0)
        var reads = 0
        repeat(1000) { for (chunk in 0..17) assertTrue(cache.ready(chunk) { reads++; true }) }
        assertEquals(18, reads)
        assertEquals(18, cache.probes)
    }

    @Test fun `cold chunks recheck on ticket phase or world tick and never stay stale`() {
        val cache = FarProjectileReadiness<Int>()
        cache.begin(1, 0)
        assertFalse(cache.ready(0) { false })
        assertFalse(cache.ready(0) { fail("duplicate cold probe") })
        cache.begin(1, 1)
        assertTrue(cache.ready(0) { true })
        cache.begin(2, 1)
        assertFalse(cache.ready(0) { false })
        cache.clear()
        assertTrue(cache.ready(0) { true })
    }

    @Test fun `unique chunk probes remain bounded but cached work is not starved`() {
        val cache = FarProjectileReadiness<Int>(2)
        cache.begin(1, 0)
        assertTrue(cache.ready(1) { true })
        assertFalse(cache.ready(2) { false })
        assertFalse(cache.ready(3) { fail("probe budget exceeded") })
        assertTrue(cache.ready(1) { fail("known ready corridor must not consume budget again") })
        assertEquals(2, cache.probes)
    }
}
