package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleWeaponSlots
import com.google.gson.JsonParser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreControlsTest {
    @Test fun `munitions groups remain in independent weapon slots when ammunition is exhausted`() {
        for (category in listOf("CRUISE_MISSILE", "COORDINATE_MISSILE", "LASER_GUIDED", "AIR_TO_AIR")) {
            val used = IntArray(3)
            fun groups() = AircraftStoreWeapons.collect(used.indices.map { index ->
                AircraftStoreWeapons.Equipped(if (index < 2) "test:first" else "test:second",
                    JsonParser.parseString("""{"Category":"$category","Guidance":{}}""").asJsonObject,
                    AircraftStoreWeapons.Member("mount_$index", emptyList(), 1, 1 - used[index]))
            }) { null }
            fun valid() = listOf(groups().indices.toList())
            var slots = VehicleWeaponSlots.normalize(valid(), listOf(0), listOf(1))
            assertEquals(2, groups().size)
            val identities = groups().map { it.weaponId }
            used[0] = 1; used[1] = 1
            slots = VehicleWeaponSlots.normalize(valid(), slots.primary, slots.secondary)
            assertEquals(VehicleWeaponSlots(listOf(0), listOf(1)), slots)
            assertEquals(identities, groups().map { it.weaponId })
            assertEquals(listOf(0, 1), groups().map { it.ammo })
            used[2] = 1
            slots = VehicleWeaponSlots.normalize(valid(), slots.primary, slots.secondary)
            assertEquals(VehicleWeaponSlots(listOf(0), listOf(1)), slots)
            assertEquals(identities, groups().map { it.weaponId })
        }
    }
    @Test fun `only normal weapon slot inputs may dispatch coordinate stores`() {
        for (category in listOf("CRUISE_MISSILE", "COORDINATE_MISSILE")) {
            assertDoesNotThrow { AircraftStoreControls.requireFireInput(category, true) }
            val failure = assertThrows(IllegalArgumentException::class.java) {
                AircraftStoreControls.requireFireInput(category, false)
            }
            assertEquals(AircraftStoreControls.COORDINATE_INPUT_HINT, failure.message)
        }
        for (category in listOf("LASER_GUIDED", "AIR_TO_AIR", "BOMB", "ROCKET_POD")) {
            assertDoesNotThrow { AircraftStoreControls.requireFireInput(category, false) }
        }
        assertTrue(AircraftStoreControls.selectable("LASER_GUIDED", false, 1))
        assertTrue(AircraftStoreControls.selectable("LASER_GUIDED", false, 0))
        assertTrue(AircraftStoreControls.selectable("AIR_TO_AIR", true, 1))
        assertTrue(AircraftStoreControls.selectable("AIR_TO_AIR", true, 0))
        assertFalse(AircraftStoreControls.selectable("AIR_TO_AIR", false, 1))
        assertFalse(AircraftStoreControls.selectable("VISUAL_ONLY", false, 1))
    }

    @Test fun `legacy cycle and fire exclude coordinate pylons even after firing or refit`() {
        val json = JsonParser.parseString("""{
            "Vehicle":"00000000-0000-0000-0000-000000000001","EntityId":7,"Revision":1,
            "Definition":{"Schema":1,"Name":"Mixed controls","Singles":[
                {"Id":"cruise","Name":"Cruise","Position":[0,1,0],"AllowedStores":["test:cruise","test:laser"]},
                {"Id":"gps","Name":"GPS","Position":[1,1,0],"AllowedStores":["test:gps"]},
                {"Id":"laser","Name":"Laser","Position":[2,1,0],"AllowedStores":["test:laser"]},
                {"Id":"empty","Name":"Empty","Position":[3,1,0],"AllowedStores":["test:cruise"]}
            ]},
            "Stores":{
                "test:cruise":{"Schema":1,"Name":"Cruise","Category":"CRUISE_MISSILE","CoordinateProfile":"ballistics:basic","Capacity":1},
                "test:gps":{"Schema":1,"Name":"GPS","Category":"COORDINATE_MISSILE","CoordinateProfile":"ballistics:srbm","Capacity":1},
                "test:laser":{"Schema":1,"Name":"Laser","Category":"LASER_GUIDED","Capacity":1}
            },
            "Selections":{"cruise":"test:cruise","gps":"test:gps","laser":"test:laser"},
            "Fired":{"cruise":1}
        }""").asJsonObject
        fun shortcuts() = AircraftArmamentSnapshot.decode(json)!!.legacyShortcutMounts().map { it.id }
        assertEquals(listOf("laser"), shortcuts())
        json.getAsJsonObject("Selections").addProperty("cruise", "test:laser")
        assertEquals(listOf("cruise", "laser"), shortcuts())
        json.getAsJsonObject("Selections").addProperty("cruise", "test:cruise")
        assertEquals(listOf("laser"), shortcuts())
        json.getAsJsonObject("Selections").remove("laser")
        assertTrue(shortcuts().isEmpty(), "empty pylons must not mask the coordinate control hint")
    }

}
