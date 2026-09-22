package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarVehicleRegistryTest {
    private class Entity(var accessible: Boolean = true, var removed: Boolean = false)
    private fun registry(capacity: Int = 4) = FarVehicleRegistry<Entity>(capacity,
        removed = { it.removed }, accessible = { it.accessible })

    @Test fun `temporary tracking end preserves membership and resumes without another join`() {
        val registry = registry()
        val tank = Entity()
        registry.joined(tank)
        assertEquals(listOf(tank), registry.visible())
        tank.accessible = false
        registry.left(tank)
        repeat(100) { assertTrue(registry.visible().isEmpty()) }
        assertEquals(1, registry.size)
        tank.accessible = true
        assertEquals(listOf(tank), registry.visible())
        tank.removed = true
        registry.left(tank)
        assertEquals(0, registry.size)
    }

    @Test fun `entity spawned before chunk accessibility is retained and later published`() {
        val registry = registry()
        val aircraft = Entity(accessible = false)
        registry.joined(aircraft)
        assertTrue(registry.visible().isEmpty())
        aircraft.accessible = true
        assertEquals(listOf(aircraft), registry.visible())
        aircraft.accessible = false
        registry.left(aircraft)
        aircraft.removed = true
        assertTrue(registry.visible().isEmpty())
        assertEquals(0, registry.size)
    }

    @Test fun `capacity and server reset remain bounded`() {
        val registry = registry(2)
        val first = Entity()
        registry.joined(first)
        registry.joined(first)
        registry.joined(Entity())
        registry.joined(Entity())
        assertEquals(2, registry.size)
        registry.clear()
        assertEquals(0, registry.size)
        assertTrue(registry.visible().isEmpty())
    }
}
