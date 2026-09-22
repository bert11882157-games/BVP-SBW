package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainHandshakeTest {
    @Test fun `unrelated terrain changes keep an already covered vehicle ready`() {
        val gate = FarTerrainHandshake("s", "d")
        gate.update(576, setOf(1, 2, 3), emptySet())
        for (key in 1L..3L) gate.delivered(1, key)
        gate.acknowledge("s", "d", 1)

        gate.update(576, setOf(1, 2, 3, 4), setOf(3))

        assertTrue(gate.readyFor(setOf(1, 2)), "Unchanged acknowledged terrain remains usable")
        assertFalse(gate.readyFor(setOf(2, 3)), "Changed cover must be rebuilt and acknowledged")
        assertFalse(gate.readyFor(setOf(2, 4)), "New terrain must be delivered and acknowledged")
        assertFalse(gate.acknowledge("s", "d", 1), "A stale acknowledgement cannot restore changed cover")
        assertFalse(gate.ready())
    }

    @Test fun `partial readiness reveals only the vehicle with complete intervening terrain`() {
        val gate = FarTerrainHandshake("s", "d")
        gate.update(384, setOf(1, 2, 3), emptySet())
        gate.delivered(1, 1); gate.delivered(1, 2)
        assertTrue(gate.acknowledge("s", "d", 1, setOf(1, 2)))
        assertTrue(gate.readyFor(setOf(1, 2)))
        assertFalse(gate.readyFor(setOf(2, 3)))
        assertFalse(gate.readyFor(setOf(4)))
        assertFalse(gate.acknowledge("s", "d", 1, setOf(1, 2, 3)))
        assertFalse(gate.ready())
    }

    @Test fun `removed then restored terrain needs a fresh delivery and acknowledgement`() {
        val gate = FarTerrainHandshake("s", "d")
        gate.update(576, setOf(1, 2), emptySet())
        gate.delivered(1, 1); gate.delivered(1, 2)
        gate.acknowledge("s", "d", 1)
        gate.update(576, setOf(1), emptySet())
        assertTrue(gate.readyFor(setOf(1)))
        assertFalse(gate.readyFor(setOf(2)))
        gate.update(576, setOf(1, 2), emptySet())
        assertFalse(gate.readyFor(setOf(2)))
        assertFalse(gate.acknowledge("s", "d", 3, setOf(2)))
        gate.delivered(3, 2)
        assertFalse(gate.readyFor(setOf(2)))
        assertTrue(gate.acknowledge("s", "d", 3, setOf(1, 2)))
        assertTrue(gate.readyFor(setOf(2)))
    }
    @Test fun `delivery and current client acknowledgement both precede visibility`() {
        val gate = FarTerrainHandshake("session", "overworld")
        assertFalse(gate.ready())
        assertTrue(gate.update(384, setOf(1, 2), emptySet()))
        assertFalse(gate.acknowledge("session", "overworld", 1))
        assertFalse(gate.delivered(1, 3))
        assertFalse(gate.delivered(0, 1))
        assertTrue(gate.delivered(1, 1))
        assertFalse(gate.acknowledge("session", "overworld", 1))
        assertTrue(gate.delivered(1, 2))
        assertFalse(gate.ready())
        assertFalse(gate.acknowledge("old-session", "overworld", 1))
        assertFalse(gate.acknowledge("session", "nether", 1))
        assertTrue(gate.acknowledge("session", "overworld", 1))
        assertTrue(gate.ready())
    }

    @Test fun `new cover invalidates readiness and only unchanged chunks can be reused`() {
        val gate = FarTerrainHandshake("s", "d")
        gate.update(384, setOf(1, 2), emptySet())
        gate.delivered(1, 1); gate.delivered(1, 2); gate.acknowledge("s", "d", 1)
        assertFalse(gate.update(384, setOf(1, 2), emptySet()))
        assertTrue(gate.ready())
        assertTrue(gate.update(384, setOf(1, 2), setOf(2)))
        assertFalse(gate.ready())
        assertEquals(setOf(1L), gate.sent)
        assertFalse(gate.acknowledge("s", "d", 1))
        assertFalse(gate.delivered(1, 2))
        gate.delivered(2, 2)
        assertTrue(gate.acknowledge("s", "d", 2))
        assertTrue(gate.ready())
    }

    @Test fun `movement shrink and full replacement evict obsolete chunks`() {
        val gate = FarTerrainHandshake("s", "d")
        gate.update(384, setOf(1, 2, 3), emptySet())
        for (key in 1L..3L) gate.delivered(1, key)
        gate.acknowledge("s", "d", 1)
        gate.update(192, setOf(2, 4), emptySet())
        assertEquals(setOf(2L), gate.sent)
        assertFalse(gate.ready())
        gate.delivered(2, 4); gate.acknowledge("s", "d", 2)
        assertTrue(gate.ready())
        gate.update(192, emptySet(), emptySet())
        assertTrue(gate.sent.isEmpty())
        assertTrue(gate.desired.isEmpty())
        assertFalse(gate.delivered(3, 4))
    }
}
