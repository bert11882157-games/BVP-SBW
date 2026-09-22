package com.atsuishio.superbwarfare.api.vehicle.weapon

import net.minecraft.resources.ResourceLocation
import com.atsuishio.superbwarfare.data.vehicle.subdata.SeatInfo
import com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons
import com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups
import com.google.gson.JsonObject
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VehicleFixedGunBanksTest {
    @Test
    fun `seat serializer preserves exact bank order and old definitions remain ungrouped`() {
        val old = Json.decodeFromString(SeatInfo.serializer(), """{"Weapons":["Cannon"]}""")
        assertTrue(old.fixedGunBank.isEmpty())
        val seat = Json.decodeFromString(SeatInfo.serializer(),
            """{"Weapons":["Cannon","LeftMG","RightMG"],"FixedGunBank":["RightMG","Cannon","LeftMG"]}""")
        assertEquals(listOf("Cannon", "LeftMG", "RightMG"), seat.weapons())
        assertEquals(listOf("RightMG", "Cannon", "LeftMG"), seat.fixedGunBank)
        val encoded = Json.encodeToString(SeatInfo.serializer(), seat)
        assertEquals(seat.fixedGunBank, Json.decodeFromString(SeatInfo.serializer(), encoded).fixedGunBank)
        assertThrows(Exception::class.java) {
            Json.decodeFromString(SeatInfo.serializer(), """{"FixedGunBank":"Cannon"}""")
        }
    }

    @Test
    fun `bank preserves exact authored order and rejects aliases`() {
        val owned = listOf("Cannon", "LeftMG", "RightMG")
        assertArrayEquals(
            intArrayOf(2, 0, 1),
            VehicleFixedGunBanks.memberIndices(listOf("RightMG", "Cannon", "LeftMG"), owned),
        )
        assertNull(VehicleFixedGunBanks.memberIndices(listOf("Cannon", "Cannon"), owned))
        assertNull(VehicleFixedGunBanks.memberIndices(listOf("Missing"), owned))
        assertNull(VehicleFixedGunBanks.memberIndices(listOf("Cannon"), listOf("Cannon", "Cannon")))
        assertNull(VehicleFixedGunBanks.memberIndices(emptyList(), owned))
        assertNull(VehicleFixedGunBanks.memberIndices(List(9) { "Gun$it" }, List(9) { "Gun$it" }))
        assertNull(VehicleFixedGunBanks.memberIndices(listOf("bad\nname"), listOf("bad\nname")))
    }

    @Test
    fun `assembly rate determines bounded event capacity without projectile multiplication`() {
        assertEquals(1, VehicleFixedGunBanks.eventCapacity(850))
        assertEquals(3, VehicleFixedGunBanks.eventCapacity(3000))
        assertEquals(3, VehicleFixedGunBanks.eventCapacity(3396))
        assertEquals(5, VehicleFixedGunBanks.eventCapacity(6000))
        assertEquals(8, VehicleFixedGunBanks.eventCapacity(9600))
        assertNull(VehicleFixedGunBanks.eventCapacity(0))
        assertNull(VehicleFixedGunBanks.eventCapacity(9601))
        assertNull(VehicleFixedGunBanks.eventCapacity(Int.MAX_VALUE))
    }

    @Test fun `dynamic pod group accepts eight physical feeds plus main cannon without weakening fixed bank bound`() {
        val all = listOf("Cannon") + (1..4).flatMap { listOf("Pod${it}Left", "Pod${it}Right") }
        assertNull(VehicleFixedGunBanks.memberIndices(all, all))
        assertArrayEquals(IntArray(9) { it }, VehicleFixedGunBanks.memberIndices(all, all, 16))
        assertNull(VehicleFixedGunBanks.memberIndices(List(17) { "Pod$it" }, List(17) { "Pod$it" }, 16))
    }

    @Test fun `group and main trigger resolve same physical schedules for one through four paired fittings`() {
        val groups = com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups
        for (count in 1..4) {
            val pods = (1..count).flatMap { listOf("Pod${it}Left", "Pod${it}Right") }
            for (loaded in listOf(pods, pods.filterIndexed { i, _ -> i % 2 == 0 }, pods.drop(1))) {
                val group = groups.members(groups.GROUP, listOf("Cannon"), loaded)!!
                val main = groups.members("Cannon", listOf("Cannon"), loaded)!!
                assertEquals(loaded, group)
                assertEquals(listOf("Cannon") + loaded, main)
                val active = linkedMapOf<String, VehicleWeaponScheduler.RuntimeState>()
                for (name in main + group) active.putIfAbsent(name, state(3400))
                assertEquals(loaded.size + 1, active.size)
                val counts = active.mapValues { 0 }.toMutableMap()
                for (tick in 0..20) for ((name, runtime) in active) {
                    runtime.tick(tick, true)
                    while (runtime.canAttempt()) { runtime.consumeCredit(); runtime.recordAccepted(tick); counts[name] = counts.getValue(name) + 1 }
                }
                assertEquals(1, counts.values.distinct().size)
                assertTrue(counts.values.all { it in 56..58 }, "overlapping slots must not double3400RPM cadence")
            }
        }
        assertNull(groups.members("Rockets", listOf("Cannon"), listOf("Pod")))
    }

    @Test fun `store type aliases isolate gun pods while overlapping main fire shares physical schedules`() {
        fun fitted(type: String, category: String, mount: String, feeds: List<String>) =
            AircraftStoreWeapons.Equipped(type, JsonObject().apply { addProperty("Category", category) },
                AircraftStoreWeapons.Member(mount, feeds, 1, 1))
        val groups = AircraftStoreWeapons.collect(listOf(
            fitted("test:pod_a", "GUN_POD", "left", listOf("AL", "AR")),
            fitted("test:pod_a", "GUN_POD", "right", listOf("AL", "AR")),
            fitted("test:pod_b", "GUN_POD", "outer", listOf("B")),
            fitted("test:rockets", "ROCKET_POD", "centre", listOf("R")),
        )) { 1000 to 1000 }.associateBy { it.storeId }
        val podA = groups.getValue("test:pod_a")
        val podB = groups.getValue("test:pod_b")
        val allPods = listOf("AL", "AR", "B")
        val bankA = AircraftGunPodGroups.members(podA.weaponId, listOf("Cannon"), allPods, podA)!!
        val bankB = AircraftGunPodGroups.members(podB.weaponId, listOf("Cannon"), allPods, podB)!!
        assertEquals(listOf("AL", "AR"), bankA)
        assertEquals(listOf("B"), bankB)
        assertEquals(2000, podA.ammo, "repeated mappings must not duplicate ammunition")
        val rockets = groups.getValue("test:rockets")
        assertNull(AircraftGunPodGroups.members(rockets.weaponId, listOf("Cannon"), allPods, rockets))
        val main = AircraftGunPodGroups.members("Cannon", listOf("Cannon"), allPods)!!
        val schedules = linkedMapOf<String, VehicleWeaponScheduler.RuntimeState>()
        for (channel in main + bankA + bankB) schedules.putIfAbsent(channel, state(850))
        assertEquals(setOf("Cannon", "AL", "AR", "B"), schedules.keys)
        val counts = schedules.mapValues { 0 }.toMutableMap()
        for (tick in 0..20) for ((channel, runtime) in schedules) {
            runtime.tick(tick, true)
            while (runtime.canAttempt()) {
                runtime.consumeCredit(); runtime.recordAccepted(tick)
                counts[channel] = counts.getValue(channel) + 1
            }
        }
        assertTrue(counts.values.all { it in 14..15 }, "overlapping aliases must retain one 850 RPM feed")
    }

    private fun state(rpm: Int): VehicleWeaponScheduler.RuntimeState {
        val events = requireNotNull(VehicleFixedGunBanks.eventCapacity(rpm))
        return VehicleWeaponScheduler.RuntimeState(VehicleWeaponScheduleProfile(
            id = ResourceLocation("test", "fixed_bank"),
            bulletRpm = rpm,
            eventRpm = rpm,
            projectilesPerEvent = 1,
            soundIntervalProjectiles = 1,
            releaseGraceTicks = 0,
            preserveAcceptedCadenceAcrossPresses = true,
            maxCatchUpEvents = maxOf(2, events),
        ))
    }

    @Test
    fun `M61 earns five distinct events after its initial event`() {
        val state = state(6000)
        state.tick(0, true)
        assertTrue(state.canAttempt())
        state.consumeCredit()
        state.recordAccepted(0)
        assertFalse(state.canAttempt())

        state.tick(1, true)
        repeat(5) {
            assertTrue(state.canAttempt())
            state.consumeCredit()
            state.recordAccepted(1)
        }
        assertFalse(state.canAttempt())
    }

    @Test
    fun `three independent guns start together and retain cadence across repress`() {
        val guns = List(3) { state(850) }
        for (gun in guns) {
            gun.tick(0, true)
            assertTrue(gun.canAttempt())
            gun.consumeCredit()
            gun.recordAccepted(0)
            gun.tick(1, false)
            gun.tick(1, true)
            assertFalse(gun.canAttempt(), "repress must not manufacture a free shot")
            gun.tick(2, true)
            assertTrue(gun.canAttempt())
        }
    }

    @Test
    fun `one minute sustained cadence retains fractions at high rates and multiple guns`() {
        for (rpm in listOf(550, 730, 850, 1000, 1080, 1300, 1350, 1400, 1700, 1800, 3400, 4500, 6000, 8000)) {
            val guns = List(3) { state(rpm) }
            val counts = IntArray(guns.size)
            for (tick in 0..1200) for ((index, gun) in guns.withIndex()) {
                gun.tick(tick, true)
                var inTick = 0
                while (gun.canAttempt()) {
                    assertTrue(++inTick <= maxOf(2, requireNotNull(VehicleFixedGunBanks.eventCapacity(rpm))))
                    gun.consumeCredit()
                    gun.recordAccepted(tick)
                    counts[index]++
                }
            }
            // One immediate shot followed by one full minute; floating rounding may defer one.
            for (count in counts) assertTrue(count in rpm..rpm + 1, "$rpm RPM accepted $count events")
            assertEquals(1, counts.distinct().size, "independent feeds must retain equal cadence")
        }
    }

    @Test
    fun `schedule identity cannot transfer to a different weapon at a reused index`() {
        assertNotEquals(
            VehicleWeaponScheduler.ScheduleKey(0, 1, "LeftMG"),
            VehicleWeaponScheduler.ScheduleKey(0, 1, "Cannon"),
        )
    }
}
