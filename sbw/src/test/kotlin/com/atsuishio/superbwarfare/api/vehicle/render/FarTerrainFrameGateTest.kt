package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainFrameGateTest {
    @Test fun `a plan and acknowledgement cannot draw before a complete frame`() {
        val gate = FarTerrainFrameGate()
        assertFalse(gate.acceptFrame(-1, 0))
        gate.plan(1, 1)
        gate.acknowledge()
        assertFalse(gate.ready(1))
        assertTrue(gate.acceptFrame(1, 2))
        assertFalse(gate.ready(2))
        gate.commit(1)
        assertTrue(gate.ready(2))
    }

    @Test fun `retained frame survives a new plan and its next acknowledgement`() {
        val gate = FarTerrainFrameGate()
        gate.plan(1, 0)
        gate.commit(1)
        gate.plan(2, 10)
        assertEquals(-1L, gate.acknowledged)
        assertEquals(1L, gate.committed)
        assertTrue(gate.ready(10))
        assertTrue(gate.acceptFrame(2, 11), "Reused terrain does not need another ACK round trip")
        gate.commit(2)
        assertTrue(gate.ready(11))
        gate.acknowledge()
        assertEquals(2L, gate.acknowledged)
    }

    @Test fun `silence retains covered frames but stale traffic cannot resurrect cleared state`() {
        val gate = FarTerrainFrameGate()
        gate.plan(2, 0)
        gate.commit(2)
        assertFalse(gate.plan(1, 90))
        assertFalse(gate.acceptFrame(1, 90))
        gate.commit(1)
        assertEquals(2L, gate.committed)
        assertTrue(gate.ready(100))
        assertTrue(gate.ready(12000), "Network silence cannot invalidate already received terrain")
        assertEquals(12000L, gate.age(12000), "Rejected stale traffic must not refresh diagnostic activity")
        gate.clear()
        gate.commit(2)
        assertFalse(gate.acceptFrame(2, 102))
        assertFalse(gate.ready(102))
    }

    @Test fun `continuous unrelated invalidations do not interrupt covered vehicle publication`() {
        val server = FarTerrainHandshake("s", "d")
        val client = FarTerrainFrameGate()
        val targetCover = setOf(1L, 2L)
        server.update(576, setOf(1, 2, 3), emptySet())
        client.plan(server.revision, 0)
        for (chunk in 1L..3L) server.delivered(server.revision, chunk)
        server.acknowledge("s", "d", server.revision)
        client.acknowledge()
        client.commit(server.revision)

        for (tick in 1L..120L) {
            server.update(576, setOf(1, 2, 3), setOf(3))
            client.plan(server.revision, tick)
            assertTrue(server.readyFor(targetCover), "Publication interrupted on tick $tick")
            assertTrue(client.ready(tick), "Client frame interrupted on tick $tick")
            assertTrue(client.acceptFrame(server.revision, tick))
            client.commit(server.revision)
            assertFalse(server.readyFor(setOf(3L)), "Changed terrain must remain hidden")
        }
    }
}
