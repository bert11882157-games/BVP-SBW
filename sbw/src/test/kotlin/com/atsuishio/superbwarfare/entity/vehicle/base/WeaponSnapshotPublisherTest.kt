package com.atsuishio.superbwarfare.entity.vehicle.base

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WeaponSnapshotPublisherTest {
    private data class State(var ammo: Int, var reload: Int = 0, var beltPosition: Int = 0)

    @Test fun `one hundred idle four weapon vehicles allocate no new weapon snapshots over 200 ticks`() {
        val live = List(100) { (0..3).associate { "weapon$it" to State(200) } }
        val published = live.map { vehicle -> vehicle.mapValues { it.value.copy() } }
        var copies = 0
        repeat(200) {
            for (i in live.indices) assertNull(WeaponSnapshotPublisher.changedSnapshot(live[i], published[i]) {
                copies++; it.copy()
            })
        }
        assertEquals(0, copies) // Old loop made 80,000 unconditional GunData copies here.
    }

    @Test fun `ammo reload and belt changes copy only their weapon and remain detached`() {
        val live = linkedMapOf("cannon" to State(1), "coax" to State(200))
        val previous = live.mapValues { it.value.copy() }
        live.getValue("coax").apply { ammo--; reload = 3; beltPosition = 1 }
        var copies = 0
        val next = WeaponSnapshotPublisher.changedSnapshot(live, previous) { copies++; it.copy() }!!
        assertEquals(1, copies)
        assertSame(previous["cannon"], next["cannon"])
        assertNotSame(live["coax"], next["coax"])
        live.getValue("coax").ammo--
        assertEquals(199, next.getValue("coax").ammo)
        assertEquals(200, previous.getValue("coax").ammo)
    }

    @Test fun `configuration removal publishes without copying retained weapons`() {
        val old = mapOf("cannon" to State(1), "coax" to State(200))
        val live = mapOf("cannon" to old.getValue("cannon").copy())
        val next = WeaponSnapshotPublisher.changedSnapshot(live, old) { fail("unchanged weapon") }!!
        assertEquals(setOf("cannon"), next.keys)
        assertSame(old["cannon"], next["cannon"])
    }
}
