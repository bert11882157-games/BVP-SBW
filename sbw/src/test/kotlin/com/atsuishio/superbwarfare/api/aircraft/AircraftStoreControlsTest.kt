package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProfile
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduler
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleWeaponSlots
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStoreControlsTest {
    @Test fun `coordinate launchers stay in both weapon slots after depletion`() {
        for (category in listOf("CRUISE_MISSILE", "COORDINATE_MISSILE")) {
            val used = IntArray(3)
            fun valid() = listOf(used.indices.filter {
                AircraftStoreControls.selectable(category, false, 1 - used[it])
            })
            var selected = VehicleWeaponSlots.normalize(valid(), listOf(0), listOf(1))
            val primary = trigger()
            val secondary = trigger()
            val launched = mutableListOf<Int>()

            fun attempt(slot: Int, now: Long) = AircraftStoreLaunchTransaction.execute(
                used[slot], 1, null, now,
                launch = { launched += slot }, commit = { used[slot]++ })

            primary.tick(0, true)
            assertTrue(primary.canAttempt())
            primary.consumeCredit()
            attempt(selected.primary[0], 0)
            primary.recordAccepted(0)
            selected = VehicleWeaponSlots.normalize(valid(), selected.primary, selected.secondary)
            assertEquals(VehicleWeaponSlots(listOf(0), listOf(1)), selected)

            for (tick in 1..40) {
                primary.tick(tick, true)
                assertFalse(primary.canAttempt(), "holding primary must not release another pylon")
                assertEquals(listOf(0, 1, 2), valid()[0])
            }
            assertEquals(listOf(0), launched)

            // A fresh press on the depleted primary still addresses that same empty launcher.
            primary.releaseEdge()
            primary.tick(41, true)
            assertTrue(primary.canAttempt())
            assertThrows(IllegalArgumentException::class.java) { attempt(selected.primary[0], 41) }
            assertEquals(listOf(0), launched)
            assertArrayEquals(intArrayOf(1, 0, 0), used)

            // The independent secondary and an explicitly selected next primary remain usable.
            secondary.tick(42, true)
            assertTrue(secondary.canAttempt())
            secondary.consumeCredit()
            attempt(selected.secondary[0], 42)
            secondary.recordAccepted(42)
            selected = VehicleWeaponSlots.normalize(valid(), selected.primary, selected.secondary)
            assertEquals(VehicleWeaponSlots(listOf(0), listOf(1)), selected)
            selected = selected.select(valid(), 0, 2, 1)!!
            attempt(selected.primary[0], 43)
            assertEquals(listOf(0, 1, 2), launched)
            assertArrayEquals(intArrayOf(1, 1, 1), used)
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
        assertFalse(AircraftStoreControls.selectable("LASER_GUIDED", false, 0))
        assertTrue(AircraftStoreControls.selectable("AIR_TO_AIR", true, 1))
        assertFalse(AircraftStoreControls.selectable("AIR_TO_AIR", true, 0))
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

    private fun trigger() = VehicleWeaponScheduler.RuntimeState(VehicleWeaponScheduleProfile(
        ResourceLocation("test", "coordinate_controls"), 120, 120, 1, 1,
        repeatWhileHeld = false, releaseGraceTicks = 0,
    ))
}
