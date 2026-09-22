package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarProjectileResidencyTest {
    @Test fun `future loading can replace obsolete trail but never the current sweep`() {
        val leases = FarProjectileResidency<Int>(8, 4)
        assertTrue(leases.reserve((0..7).toSet(), 0, true))
        assertFalse(leases.reserve(setOf(8, 9), 1, false))
        assertTrue(leases.reserve(setOf(6, 7), 3, true))
        assertTrue(leases.reserve(setOf(8, 9, 10, 11), 3, false))
        assertTrue(leases.keys().containsAll(setOf(6, 7, 8, 9, 10, 11)))
        assertFalse(leases.reserve(setOf(12), 3, false))
        assertEquals(8, leases.size)
    }

    @Test fun `speculation is bounded and yields immediately to real collision paths`() {
        val leases = FarProjectileResidency<Int>(8, 4)
        assertTrue(leases.reserve(setOf(0, 1, 2, 3), 0, false))
        assertFalse(leases.reserve(setOf(4), 0, false))
        assertTrue(leases.reserve(setOf(4, 5, 6, 7, 8, 9), 0, true))
        assertEquals(8, leases.size)
        assertTrue(leases.keys().containsAll(setOf(4, 5, 6, 7, 8, 9)))
    }

    @Test fun `failed admission cannot perpetually renew a partial saturated corridor`() {
        val leases = FarProjectileResidency<Int>(4, 2, 4)
        assertTrue(leases.reserve(setOf(0, 1, 2, 3), 0, true))
        for (tick in 1L..2L) assertFalse(leases.reserve(setOf(0, 4), tick, true))
        assertTrue(leases.reserve(setOf(4, 5, 6, 7), 3, true))
        assertEquals(setOf(4, 5, 6, 7), leases.keys())
        leases.expire(8)
        assertEquals(0, leases.size)
    }

    @Test fun `continuous fast flight evicts obsolete trail rather than blocking at capacity`() {
        val leases = FarProjectileResidency<Int>()
        for (tick in 0L..139L) {
            val x = tick.toInt() * 4
            val current = (x until x + 18).toSet()
            assertTrue(leases.reserve(current, tick, true), "actual sweep at tick $tick")
            for (ahead in 1..10) leases.reserve((x + 4 * ahead until x + 4 * ahead + 18).toSet(), tick, false)
            assertTrue(leases.keys().containsAll(current))
            assertTrue(leases.size <= 256)
        }
    }

    @Test fun `leases survive player handoff and required sweep wins over old speculative queues`() {
        val leases = FarProjectileResidency<Int>(6, 4)
        assertTrue(leases.reserve(setOf(1, 2, 3), 0, true))
        assertTrue(leases.reserve(setOf(4, 5, 6), 0, false))
        assertFalse(leases.reserve(setOf(1, 7, 8, 9, 10), 1, true))
        assertTrue(leases.required(1, 2))
        assertTrue(leases.reserve(setOf(4, 5, 6, 7, 8, 9), 3, true))
        assertEquals(setOf(4, 5, 6, 7, 8, 9), leases.keys())
        leases.removeIf { it % 2 == 0 }
        assertEquals(setOf(5, 7, 9), leases.keys())
        leases.clear()
        assertEquals(0, leases.size)
    }
}
