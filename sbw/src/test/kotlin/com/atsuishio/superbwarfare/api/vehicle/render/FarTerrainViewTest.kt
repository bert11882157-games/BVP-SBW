package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FarTerrainViewTest {
    @Test fun `view hints validate identities and remain separate from acquisition`() {
        val retained = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        val view = FarTerrainView.parse(listOf(retained.toString(), foreign.toString()), -32.0, 12.0)!!
        val subscribed = FarVehicleSubscription.reconcile(setOf(retained), setOf(retained, foreign), emptyList(), 128)
        assertEquals(setOf(retained), subscribed)
        assertEquals(setOf(retained), view.vehicles.intersect(subscribed))
        assertEquals(-32.0, view.offsetX)
        assertNotNull(FarTerrainView.parse(emptyList(), 128.0, 0.0))
    }

    @Test fun `invalid view requests cannot expand terrain or allocate unbounded priority lists`() {
        val id = UUID.randomUUID().toString()
        assertNull(FarTerrainView.parse(listOf(id, id), 0.0, 0.0))
        assertNull(FarTerrainView.parse(listOf("not-a-uuid"), 0.0, 0.0))
        assertNull(FarTerrainView.parse(List(257) { UUID.randomUUID().toString() }, 0.0, 0.0))
        assertNull(FarTerrainView.parse(emptyList(), Double.NaN, 0.0))
        assertNull(FarTerrainView.parse(emptyList(), 0.0, Double.POSITIVE_INFINITY))
        assertNull(FarTerrainView.parse(emptyList(), 128.0, 1.0))
    }

    @Test fun `received cover remains ahead of offscreen demand during a full-cache turn`() {
        val required = (0L until 400L).toSet()
        val recent = (400L until 1500L).toSet()
        val background = (1500L until 3500L).toSet()
        val retained = FarTerrainDelivery.appendWithinBudget(required, recent, emptySet(), 0)
        val final = FarTerrainDelivery.appendWithinBudget(retained, background, emptySet(), 0)
        assertEquals(FarTerrainPolicy.MAX_CHUNKS, final.size)
        val server = FarTerrainHandshake("s", "d")
        server.update(960, final, emptySet())
        (required + recent).forEach { server.delivered(server.revision, it) }
        assertTrue(server.acknowledge("s", "d", server.revision, server.sent))
        assertTrue(server.readyFor(recent), "Turning back uses existing cover without another transfer")
        assertFalse(server.readyFor(background), "Unsupplied cover must still block drawing")
    }

    @Test fun `speculative motion cannot consume the whole received-cover cache`() {
        val required = (0L until 400L).toSet()
        val prediction = (0L until 5000L).toList()
        val recent = (5000L until 6520L).toList()
        val predicted = FarTerrainDelivery.appendWithinBudget(required, prediction, emptySet(), 0, 128)
        val plan = FarTerrainDelivery.appendWithinBudget(predicted, recent, emptySet(), 0)
        assertEquals(528, predicted.size)
        assertTrue(plan.containsAll(required))
        assertTrue(plan.containsAll(recent), "Speculation must leave room for already-received cover")
        assertEquals(FarTerrainPolicy.MAX_CHUNKS, plan.size)
    }
}
