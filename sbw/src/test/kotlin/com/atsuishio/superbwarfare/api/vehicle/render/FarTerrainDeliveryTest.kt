package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainDeliveryTest {
    @Test fun `camera movement reprioritizes unsent cover even with unchanged membership`() {
        val gate = FarTerrainHandshake("s", "d")
        val desired = (0L until FarTerrainPolicy.MAX_CHUNKS.toLong()).toSet()
        assertTrue(gate.update(576, desired, emptySet()))
        val required = desired.toList().takeLast(FarTerrainDelivery.PACKETS_PER_TICK).toSet()
        val order = FarTerrainDelivery.prioritize(required, desired)
        assertFalse(gate.update(576, order, emptySet()))
        assertEquals(desired, order)
        val firstDelivery = order.take(FarTerrainDelivery.PACKETS_PER_TICK).toSet()
        firstDelivery.forEach { gate.delivered(gate.revision, it) }
        assertTrue(gate.acknowledge("s", "d", gate.revision, firstDelivery))
        assertTrue(gate.readyFor(required), "Spare terrain must not delay the current sightline")
        assertFalse(gate.ready(), "Prioritization must not claim undelivered terrain is ready")
    }

    @Test fun `prediction cannot displace required cover and respects shared global capacity`() {
        val required = (0L until 490L).toSet()
        val prediction = (480L until 3000L).toSet()
        val shared = (0L until 500L).toSet()
        val plan = FarTerrainDelivery.appendWithinBudget(required, prediction, shared,
            FarTerrainPolicy.GLOBAL_CHUNKS - 5)
        assertTrue(plan.containsAll(required))
        assertEquals(505, plan.size)
        assertEquals(5, plan.count { it !in shared })
        assertEquals(FarTerrainPolicy.MAX_CHUNKS,
            FarTerrainDelivery.appendWithinBudget(required, prediction, emptySet(), 0).size)
    }

    @Test fun `recorded fast flight sightline can be delivered before padding at a full quota`() {
        // Reproduction: observer (-1111, 698), MiG-15 at (-665, 461), 79 required chunks unsent.
        val required = FarTerrainPolicy.renderCoverage(-1111.096, 697.949,
            -669.395, 456.709, -661.395, 464.709)
        val unrelated = (100000L until 100000L + FarTerrainPolicy.MAX_CHUNKS).toSet()
        val plan = FarTerrainDelivery.appendWithinBudget(required, unrelated, emptySet(), 0)
        val retainedOrder = plan.reversed().toSet()
        val gate = FarTerrainHandshake("s", "d")
        gate.update(576, retainedOrder, emptySet())
        val queue = FarTerrainDelivery.prioritize(required, retainedOrder).iterator()
        repeat((required.size + FarTerrainDelivery.PACKETS_PER_TICK - 1) / FarTerrainDelivery.PACKETS_PER_TICK) {
            repeat(FarTerrainDelivery.PACKETS_PER_TICK) {
                if (queue.hasNext()) gate.delivered(gate.revision, queue.next())
            }
        }
        assertTrue(gate.acknowledge("s", "d", gate.revision, gate.sent.toSet()))
        assertTrue(gate.readyFor(required))
        assertEquals(FarTerrainPolicy.MAX_CHUNKS, plan.size)
        assertTrue(gate.sent.size < plan.size, "Readiness must not wait for the whole spare cache")
    }
}
