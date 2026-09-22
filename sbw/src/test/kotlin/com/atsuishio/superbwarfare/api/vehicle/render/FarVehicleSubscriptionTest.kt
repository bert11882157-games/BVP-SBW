package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FarVehicleSubscriptionTest {
    @Test fun `five times distance admits then unlimited travel retains membership`() {
        val id = UUID.randomUUID()
        val existing = setOf(id)
        val radius = FarTerrainPolicy.radius(12, 12)
        assertEquals(960, radius)
        var subscribed: Set<UUID> = emptySet()
        for (distance in listOf(961.0, 960.0, 1500.0, 8192.0, 25_000_000.0)) {
            val nearby = if (FarTerrainPolicy.inside(0.0, 0.0, distance, 0.0, radius)) listOf(id) else emptyList()
            subscribed = FarVehicleSubscription.reconcile(subscribed, existing, nearby, 128)
            assertEquals(distance != 961.0, id in subscribed, "membership at $distance blocks")
        }
        assertTrue(FarVehicleSubscription.reconcile(subscribed, emptySet(), emptyList(), 128).isEmpty())
    }

    @Test fun `terrain pressure and nearer arrivals cannot displace acquired vehicles`() {
        val old = UUID.randomUUID()
        val newcomer = UUID.randomUUID()
        val both = setOf(old, newcomer)
        assertEquals(setOf(old), FarVehicleSubscription.reconcile(setOf(old), both, listOf(newcomer), 1))
        assertEquals(setOf(newcomer), FarVehicleSubscription.reconcile(setOf(old), setOf(newcomer), listOf(newcomer), 1))
        assertEquals(both, FarVehicleSubscription.reconcile(both, both, emptyList(), 1),
            "Lowering the admission limit does not silently evict existing subscriptions")
    }
}
