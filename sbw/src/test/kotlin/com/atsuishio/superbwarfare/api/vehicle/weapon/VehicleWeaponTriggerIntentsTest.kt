package com.atsuishio.superbwarfare.api.vehicle.weapon

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Drives the production retained-edge owner used by scheduler admission, pre-aim reads and preparation. */
class VehicleWeaponTriggerIntentsTest {
    private class Fixture {
        val pilot = UUID(0, 1)
        val gunner = UUID(0, 2)
        val seats = linkedMapOf(pilot to 0, gunner to 1)
        val selections = mutableMapOf<Pair<UUID, VehicleWeaponSlot>, List<String>>()
        val prepared = mutableListOf<Triple<VehicleWeaponSlot, List<String>, String>>()
        var resolverCalls = 0
        var unresolved = 0
        val intents = VehicleWeaponTriggerIntents<String, List<String>>(
            { seats[it] },
            { id, slot -> resolverCalls++; selections[id to slot] },
        )
        fun press(id: UUID, slot: VehicleWeaponSlot = VehicleWeaponSlot.PRIMARY, weapons: List<String> = listOf("Gun")) {
            selections[id to slot] = weapons
            intents.retain(slot, id, seats.getValue(id), "target:$id:$slot")
        }
        fun held(id: UUID, seat: Int, weapon: String = "Gun") =
            intents.holds(id, seat) { weapons, _ -> weapon in weapons }
        fun refresh(accept: Boolean = true) {
            for (slot in VehicleWeaponSlot.entries) intents.refresh(slot, { unresolved++ }) { weapons, payload, kind ->
                prepared += Triple(kind, weapons, payload)
                accept
            }
        }
    }

    @Test fun `pending press and release are visible before any preparation`() {
        val f = Fixture()
        assertFalse(f.held(f.pilot, 0))
        f.press(f.pilot)
        repeat(5) { assertTrue(f.held(f.pilot, 0)) }
        assertTrue(f.prepared.isEmpty(), "reads must not prepare shots or cadence")
        f.intents.release(VehicleWeaponSlot.PRIMARY, f.pilot)
        assertFalse(f.held(f.pilot, 0), "no last-tick or grace hold on aim path")
        f.refresh()
        assertTrue(f.prepared.isEmpty())
    }

    @Test fun `gunner takeover and release change current holders before the next tick`() {
        val f = Fixture()
        f.press(f.pilot)
        assertTrue(f.held(f.pilot, 0))
        assertFalse(f.held(f.gunner, 1))
        f.press(f.gunner)
        assertTrue(f.held(f.gunner, 1), "BVP gunner-priority policy sees the first pending edge")
        assertTrue(f.held(f.pilot, 0), "shared gun does not erase the other holder")
        f.intents.release(VehicleWeaponSlot.PRIMARY, f.gunner)
        assertFalse(f.held(f.gunner, 1))
        assertTrue(f.held(f.pilot, 0), "pilot handback is visible without a scheduler tick")
        assertTrue(f.prepared.isEmpty())
    }

    @Test fun `either slot retains its own edge and primary release leaves secondary live`() {
        val f = Fixture()
        f.press(f.pilot)
        f.press(f.pilot, VehicleWeaponSlot.SECONDARY, listOf("Secondary"))
        assertTrue(f.held(f.pilot, 0, "Secondary"))
        f.intents.release(VehicleWeaponSlot.PRIMARY, f.pilot)
        assertFalse(f.held(f.pilot, 0))
        assertTrue(f.held(f.pilot, 0, "Secondary"))
        f.intents.release(VehicleWeaponSlot.SECONDARY, f.pilot)
        assertFalse(f.held(f.pilot, 0, "Secondary"))
    }

    @Test fun `held edge follows current selection without a stale per-tick cache`() {
        val f = Fixture()
        f.press(f.pilot)
        assertTrue(f.held(f.pilot, 0))
        f.selections[f.pilot to VehicleWeaponSlot.PRIMARY] = listOf("Other")
        assertFalse(f.held(f.pilot, 0))
        assertTrue(f.held(f.pilot, 0, "Other"))
        f.refresh()
        assertEquals(listOf("Other"), f.prepared.single().second)
    }

    @Test fun `bank membership qualification can refuse without mutating the retained edge`() {
        val f = Fixture()
        f.press(f.pilot, weapons = listOf("Alias", "Left", "Right"))
        assertTrue(f.held(f.pilot, 0, "Left"))
        assertTrue(f.held(f.pilot, 0, "Right"))
        assertFalse(f.held(f.pilot, 0, "Unowned"))
        assertFalse(f.intents.holds(f.pilot, 0) { _, _ -> false }, "invalid or empty expansion rejects")
        assertTrue(f.held(f.pilot, 0, "Alias"), "refused read does not prune")
        assertTrue(f.prepared.isEmpty())
    }

    @Test fun `slot-specific resolver results are independent`() {
        val f = Fixture()
        f.press(f.pilot, weapons = listOf("PrimaryMember"))
        f.press(f.pilot, VehicleWeaponSlot.SECONDARY, listOf("PodMember"))
        assertTrue(f.intents.holds(f.pilot, 0) { weapons, slot ->
            slot == VehicleWeaponSlot.SECONDARY && "PodMember" in weapons
        })
        f.selections.remove(f.pilot to VehicleWeaponSlot.SECONDARY)
        assertFalse(f.held(f.pilot, 0, "PodMember"))
        assertTrue(f.held(f.pilot, 0, "PrimaryMember"))
    }

    @Test fun `same UUID in another seat cannot inherit its old held edge`() {
        val f = Fixture()
        f.press(f.pilot)
        f.seats[f.pilot] = 2
        assertFalse(f.held(f.pilot, 0))
        assertFalse(f.held(f.pilot, 2))
        f.refresh()
        f.seats[f.pilot] = 0
        assertFalse(f.held(f.pilot, 0), "preparation prunes stale seat context")
        f.press(f.pilot)
        assertTrue(f.held(f.pilot, 0), "a new admitted edge restores ownership")
    }

    @Test fun `dead or dismounted occupant cannot retain a current hold`() {
        val f = Fixture()
        f.press(f.pilot)
        f.seats.remove(f.pilot) // scheduler's resolver returns null for dead, absent or no longer aboard
        assertFalse(f.held(f.pilot, 0))
        f.refresh()
        f.seats[f.pilot] = 0
        assertFalse(f.held(f.pilot, 0))
        assertTrue(f.prepared.isEmpty())
        assertEquals(0, f.unresolved, "stale occupants do not produce missing-selection diagnostics")
    }

    @Test fun `unresolved selection is read-only until preparation prunes it`() {
        val f = Fixture()
        f.press(f.pilot)
        val previous = f.selections.remove(f.pilot to VehicleWeaponSlot.PRIMARY)!!
        assertFalse(f.held(f.pilot, 0))
        assertEquals(0, f.unresolved)
        f.selections[f.pilot to VehicleWeaponSlot.PRIMARY] = previous
        assertTrue(f.held(f.pilot, 0), "read must not prune an edge")
        f.selections.remove(f.pilot to VehicleWeaponSlot.PRIMARY)
        f.refresh()
        assertEquals(1, f.unresolved)
        f.selections[f.pilot to VehicleWeaponSlot.PRIMARY] = previous
        assertFalse(f.held(f.pilot, 0))
    }

    @Test fun `refused execution preparation prunes without changing other slot`() {
        val f = Fixture()
        f.press(f.pilot)
        f.press(f.pilot, VehicleWeaponSlot.SECONDARY, listOf("Other"))
        f.intents.refresh(VehicleWeaponSlot.PRIMARY, { fail("unexpected missing selection") }) { _, _, _ -> false }
        assertFalse(f.held(f.pilot, 0))
        assertTrue(f.held(f.pilot, 0, "Other"))
    }

    @Test fun `preparation preserves primary precedence insertion order and targeting payload`() {
        val f = Fixture()
        f.press(f.gunner, VehicleWeaponSlot.SECONDARY, listOf("Secondary"))
        f.press(f.pilot)
        f.press(f.gunner)
        f.refresh()
        assertEquals(listOf(VehicleWeaponSlot.PRIMARY, VehicleWeaponSlot.PRIMARY, VehicleWeaponSlot.SECONDARY), f.prepared.map { it.first })
        assertEquals("target:${f.pilot}:PRIMARY", f.prepared[0].third)
        assertEquals("target:${f.gunner}:PRIMARY", f.prepared[1].third)
        f.prepared.clear()
        repeat(4) { assertTrue(f.held(f.pilot, 0)) }
        f.refresh()
        assertEquals(3, f.prepared.size, "reads cannot consume a retained hold")
    }

    @Test fun `invalid queried seat never resolves a schedule`() {
        val f = Fixture()
        f.press(f.pilot)
        assertFalse(f.held(f.pilot, -1))
        assertFalse(f.held(f.pilot, 1))
        assertEquals(0, f.resolverCalls)
    }
}
