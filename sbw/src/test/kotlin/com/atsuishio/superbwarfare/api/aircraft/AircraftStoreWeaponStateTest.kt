package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreWeaponStateTest {
    private val definition = JsonParser.parseString("""{"Singles":[
        {"Id":"left","Position":[-1,0,0],"AllowedStores":["test:kh55","test:pod"],
         "NativeWeaponIds":{"test:pod":"left_feed"}},
        {"Id":"right","Position":[1,0,0],"AllowedStores":["test:kh55","test:pod"],
         "NativeWeaponIds":{"test:pod":"right_feed"}}
    ]}""").asJsonObject
    private val missile = JsonParser.parseString("""{"Name":"KH-55","Category":"CRUISE_MISSILE"}""").asJsonObject
    private val pod = JsonParser.parseString("""{"Name":"Pod","Category":"ROCKET_POD"}""").asJsonObject
    private fun entry(mount: String, ammo: Int) = AircraftStoreWeapons.Equipped("test:kh55", missile,
        AircraftStoreWeapons.Member(mount, emptyList(), 1, ammo))

    @Test fun `eight loaded bays produce one selectable HUD identity without resolving aliases`() {
        val state = AircraftStoreWeaponState().layout(definition, 1)
        val names = (1..8).map { "AircraftStore:belly_$it" } + "AircraftGunPods" +
            "AircraftStoreGroup:test:kh55"
        for (remaining in listOf(8, 7, 0)) {
            val groups = state.groups(remaining.toLong(), {
                (1..8).map { entry("belly_$it", if (it <= remaining) 1 else 0) }
            }) { fail("missiles have no native feeds") }
            val resolved = mutableListOf<String>()
            val indices = state.selectableIndices(names) { resolved += it; state.group(it) != null }
            assertEquals(listOf(9), indices, "retain real primary/secondary index despite hidden entries")
            assertEquals(listOf("AircraftStoreGroup:test:kh55"), resolved)
            assertEquals(remaining, groups.single().ammo)
            assertEquals(8, groups.single().capacity)
            assertSame(groups.single(), state.group("AircraftStore:belly_8"), "legacy API still resolves")
        }
    }

    @Test fun `mixed stores hide constituent feeds and reserve stable indices across refits`() {
        val state = AircraftStoreWeaponState().layout(definition, 1)
        val names = state.channels(listOf("gun", "left_feed", "right_feed"))
        assertSame(names, state.channels(listOf("gun", "left_feed", "right_feed")))
        assertEquals(listOf(0, 6, 7), state.selectableIndices(names) { true })
        assertEquals(listOf(0, 7), state.selectableIndices(names) { !it.endsWith("test:kh55") })
        assertSame(names, state.channels(listOf("gun", "left_feed", "right_feed")))
        val changed = state.channels(listOf("new_gun", "gun", "left_feed", "right_feed"))
        assertEquals("new_gun", changed.first())
        assertEquals(names.size + 1, changed.size)
    }

    @Test fun `unchanged virtual reads reuse the loadout without scanning mounts or native feeds`() {
        val state = AircraftStoreWeaponState().layout(definition, 1)
        var loads = 0
        val load = { loads++; listOf(entry("left", 1), entry("right", 1)) }
        val first = state.groups(0, load) { fail("no native lookup expected") }
        repeat(10000) {
            state.layout(definition, 1)
            assertSame(first, state.groups(0, load) { fail("no native lookup expected") })
        }
        assertEquals(1, loads)
        val fired = state.groups(1, { loads++; listOf(entry("left", 0), entry("right", 1)) }) { null }
        assertEquals(2, loads)
        assertEquals(1, fired.single().ammo)
        assertEquals("right", fired.single().next!!.mountId)
        assertEquals(2, first.single().ammo, "readers retain an immutable old snapshot")
    }

    @Test fun `catalogue reload definition replacement and removal invalidate even at equal equipment revision`() {
        val state = AircraftStoreWeaponState()
        var loads = 0
        fun read() = state.groups(4, { loads++; listOf(entry("left", 1)) }) { null }
        state.layout(definition, 1); read()
        state.layout(definition, 2); read()
        state.layout(definition.deepCopy(), 2); read()
        assertEquals(3, loads)
        state.layout(null, 2)
        assertTrue(state.groups(4, { emptyList() }) { null }.isEmpty())
        assertNull(state.group("AircraftStoreGroup:test:kh55"))
        assertEquals(listOf("gun"), state.channels(listOf("gun")))
        state.layout(definition, 2); read()
        assertEquals(4, loads)
    }

    @Test fun `native ammunition capacity and missing feeds refresh within the same equipment revision`() {
        val state = AircraftStoreWeaponState().layout(definition, 1)
        val feeds = mutableMapOf("left_feed" to (8 to 3), "right_feed" to (8 to 2))
        var loads = 0
        val load = {
            loads++
            listOf("left", "right").map { AircraftStoreWeapons.Equipped("test:pod", pod,
                AircraftStoreWeapons.Member(it, listOf("${it}_feed"), 99, 99)) }
        }
        val initial = state.groups(1, load, feeds::get)
        assertEquals(5, initial.single().ammo)
        assertSame(initial, state.groups(1, load, feeds::get))
        feeds["left_feed"] = 8 to 0
        assertEquals(2, state.groups(1, load, feeds::get).single().ammo)
        feeds["left_feed"] = 12 to 12
        assertEquals(20, state.groups(1, load, feeds::get).single().capacity)
        feeds.remove("right_feed")
        assertEquals(12, state.groups(1, load, feeds::get).single().ammo)
        assertEquals(1, loads)
        assertEquals(5, initial.single().ammo)
    }

    @Test fun `entity owned state cannot share a different vehicles loadout`() {
        val first = AircraftStoreWeaponState().layout(definition, 1)
        val second = AircraftStoreWeaponState().layout(definition, 1)
        first.groups(2, { listOf(entry("left", 1)) }) { null }
        second.groups(2, { emptyList() }) { null }
        assertNotNull(first.group("AircraftStoreGroup:test:kh55"))
        assertNull(second.group("AircraftStoreGroup:test:kh55"))
    }
}
