package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.function.Function

class VehicleSeatTransactionTest {
    @Test fun `occupied IFV fills passenger seats without replacing driver and rejects overflow`() {
        val owner = VehicleSeatingStateOwner(mutableListOf<String?>("driver", null, null))
        assertEquals(1, owner.insert("passenger A", null))
        assertEquals(2, owner.insert("passenger B", null))
        assertEquals(-1, owner.insert("overflow", null))
        assertEquals("driver", owner.first())
        owner.remove("passenger A")
        assertEquals(1, owner.insert("replacement", null))
        assertEquals("passenger B", owner.at(2))
    }

    @Test fun `occupied tank admits HMG operator and keeps driver seat empty when driver leaves`() {
        val owner = VehicleSeatingStateOwner(mutableListOf<String?>("driver", null))
        assertEquals(1, owner.insert("HMG operator", null))
        owner.remove("driver")
        assertNull(owner.first())
        assertEquals(0, owner.insert("new driver", null))
        assertEquals("HMG operator", owner.at(1))
    }

    @Test fun `seat shrink detaches in the old frame and supports reentrant queries`() {
        val owner = VehicleSeatingStateOwner(mutableListOf<String?>("driver", "gunner", "passenger"))
        val detached = mutableListOf<String>()
        owner.ensureSize(1) {
            assertTrue(owner.indexOf(it) >= 1)
            owner.ensureSize(1) { fail("recursive resize") }
            detached.add(it)
            owner.remove(it)
        }
        assertEquals(listOf("gunner", "passenger"), detached)
        assertEquals(listOf("driver"), owner.slots)
        owner.ensureSize(3) { fail("growing must not eject") }
        assertEquals(listOf("driver", null, null), owner.slots)
    }

    @Test fun `seat override sampled once and cannot overwrite occupants`() {
        val owner = VehicleSeatingStateOwner(mutableListOf<String?>("driver", null))
        var calls = 0
        assertEquals(1, owner.insert("gunner", Function { calls++; 1 }))
        assertEquals(1, calls)
        assertEquals(-1, owner.insert("intruder", Function { 0 }))
        assertEquals(listOf("driver", "gunner"), owner.slots)
        assertEquals(-1, owner.indexOf(null))
    }

    @Test fun `invalid resize and callback exception cannot leak resize state`() {
        val owner = VehicleSeatingStateOwner(mutableListOf<String?>("driver", "gunner"))
        assertThrows(IllegalArgumentException::class.java) { owner.ensureSize(-1) {} }
        assertThrows(IllegalStateException::class.java) { owner.ensureSize(1) { error("fixture") } }
        owner.ensureSize(1) { owner.remove(it) }
        assertEquals(listOf("driver"), owner.slots)
    }

    @Test fun `unequal selection arrays resize retaining valid prefixes`() {
        val valid = listOf(listOf(0, 1, 2), listOf(0, 2), emptyList())
        val normalized = VehicleWeaponSlots.normalize(valid, listOf(2, 2), listOf(1))
        assertEquals(listOf(2, 2, -1), normalized.primary)
        assertEquals(listOf(1, 0, -1), normalized.secondary)
        assertEquals(VehicleWeaponSlots(listOf(2), listOf(1)),
            VehicleWeaponSlots.normalize(valid.take(1), normalized.primary, normalized.secondary))
    }

    @Test fun `invalid target does not mutate prior selection and valid selection commits both lists`() {
        val valid = listOf(listOf(0, 1, 2))
        val before = VehicleWeaponSlots.normalize(valid, listOf(0), listOf(1))
        assertNull(before.select(valid, 0, 2, 2))
        assertNull(before.select(valid, 4, 2, 1))
        assertEquals(VehicleWeaponSlots(listOf(0), listOf(1)), before)
        assertEquals(VehicleWeaponSlots(listOf(2), listOf(1)), before.select(valid, 0, 2, 1))
    }
}
