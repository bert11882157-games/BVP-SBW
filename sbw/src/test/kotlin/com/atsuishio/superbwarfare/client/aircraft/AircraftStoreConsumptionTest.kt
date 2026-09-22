package com.atsuishio.superbwarfare.client.aircraft

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AircraftStoreConsumptionTest {
    private val mount = AircraftPairView("1", "1", Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0),
        listOf("test:missile"), emptyMap())
    private fun state(used: Int, capacity: Int = 1, category: String = "LASER_GUIDED", guidedAirToAir: Boolean = false) =
        AircraftArmamentSnapshot(UUID(0, 1), 1, used.toLong(),
            AircraftDefinitionView("Test", emptyList(), listOf(mount), null, emptyMap()),
            mapOf("test:missile" to AircraftStoreView("test:missile", "Missile", category,
                null, null, null, 1.0, capacity, guidedAirToAir)), mapOf("1" to "test:missile"), emptyMap(),
            null, false, "", mapOf("1" to used))

    @Test fun `paired missiles disappear in authoritative launch order`() {
        assertTrue(state(0).storePresent(mount, 0))
        assertTrue(state(0).storePresent(mount, 1))
        assertFalse(state(1).storePresent(mount, 0))
        assertTrue(state(1).storePresent(mount, 1))
        assertFalse(state(2).storePresent(mount, 0))
        assertFalse(state(2).storePresent(mount, 1))
    }
    @Test fun `multi-round racks disappear only when their own position is empty`() {
        assertTrue(state(2, 2).storePresent(mount, 0))
        assertFalse(state(3, 2).storePresent(mount, 0))
        assertTrue(state(3, 2).storePresent(mount, 1))
        assertFalse(state(4, 2).storePresent(mount, 1))
    }
    @Test fun `real AAM disappears per launch while legacy placeholders remain visual only`() {
        val operational = state(1, 1, "AIR_TO_AIR", true)
        assertFalse(operational.stores.getValue("test:missile").visualOnly)
        assertFalse(operational.storePresent(mount, 0))
        assertTrue(operational.storePresent(mount, 1))
        assertFalse(state(2, 1, "AIR_TO_AIR", true).storePresent(mount, 1))
        val legacy = state(2, 1, "AIR_TO_AIR")
        assertTrue(legacy.stores.getValue("test:missile").visualOnly)
        assertTrue(legacy.storePresent(mount, 0))
    }
    @Test fun `empty rocket pods remain attached`() {
        assertTrue(state(40, 20, "ROCKET_POD").storePresent(mount, 0))
        assertTrue(state(40, 20, "ROCKET_POD").storePresent(mount, 1))
    }
}
