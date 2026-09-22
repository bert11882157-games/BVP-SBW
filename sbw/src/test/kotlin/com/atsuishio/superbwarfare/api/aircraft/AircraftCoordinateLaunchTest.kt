package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftCoordinateLaunchTest {
    private fun store(category: String) = JsonParser.parseString("""{
        "Schema":1,"Name":"Coordinate test","Category":"$category",
        "CoordinateProfile":"ballistics:basic","Capacity":1,"LaunchDirection":[0,1,0]
    }""").asJsonObject

    @Test fun `identical coordinate stores keep individual slot labels for terminal assignments`() {
        val left = JsonParser.parseString("""{"Id":"belly_3","Name":"Belly 3"}""").asJsonObject
        val right = JsonParser.parseString("""{"Id":"belly_4","Name":"Belly 4"}""").asJsonObject
        for (category in listOf("CRUISE_MISSILE", "COORDINATE_MISSILE")) {
            val missile = store(category)
            assertEquals("Belly 3: Coordinate test", AircraftArmamentRegistry.storeWeaponName(left, missile))
            assertEquals("Belly 4: Coordinate test", AircraftArmamentRegistry.storeWeaponName(right, missile))
        }
        assertEquals("Coordinate test", AircraftArmamentRegistry.storeWeaponName(left, store("LASER_GUIDED")))
        assertEquals("Coordinate test", AircraftArmamentRegistry.storeWeaponName(left, store("AIR_TO_AIR")))
    }

    @Test fun `coordinate and cruise stores require explicit profiles and bounded launch vectors`() {
        for (category in listOf("CRUISE_MISSILE", "COORDINATE_MISSILE")) {
            val valid = store(category)
            AircraftArmamentRegistry.validate(valid, true)
            assertEquals("ballistics:basic", AircraftCoordinateLauncher.profile(valid))
            for (invalid in listOf("null", "42", "\"INVALID ID\"")) {
                val input = valid.deepCopy().apply { add("CoordinateProfile", JsonParser.parseString(invalid)) }
                assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(input, true) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                AircraftArmamentRegistry.validate(valid.deepCopy().apply { remove("CoordinateProfile") }, true)
            }
            for (invalid in listOf("[0,0,0]", "[0,129,0]", "[0,1e309,0]")) {
                val input = valid.deepCopy().apply { add("LaunchDirection", JsonParser.parseString(invalid)) }
                assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(input, true) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftArmamentRegistry.validate(store("LASER_GUIDED"), true)
        }
        assertNull(AircraftCoordinateLauncher.profile(store("AIR_TO_AIR")))
    }

    @Test fun `failed target range profile or insertion never consumes ammo cooldown or revision`() {
        for (failure in listOf("missing target", "out of range", "unknown profile", "spawn rejected")) {
            var used = 0; var lastFire: Long? = null; var revision = 0
            assertThrows(IllegalArgumentException::class.java) {
                AircraftStoreLaunchTransaction.execute(used, 2, lastFire, 100,
                    launch = { throw IllegalArgumentException(failure) },
                    commit = { used++; lastFire = 100; revision++ })
            }
            assertEquals(0, used); assertNull(lastFire); assertEquals(0, revision)
        }
    }

    @Test fun `client keeps independent coordinate stores and hides only consumed mounts`() {
        val json = JsonParser.parseString("""{
            "Vehicle":"00000000-0000-0000-0000-000000000001","EntityId":7,"Revision":1,
            "Definition":{"Schema":1,"Name":"Ground coordinate fixture","Singles":[
                {"Id":"left","Name":"Left tube","Position":[-1,1,0],"AllowedStores":["fixture:cruise"]},
                {"Id":"right","Name":"Right tube","Position":[1,1,0],"AllowedStores":["fixture:ballistic"]}
            ]},
            "Stores":{
                "fixture:cruise":{"Schema":1,"Name":"Cruise","Category":"CRUISE_MISSILE","CoordinateProfile":"ballistics:basic","Capacity":1},
                "fixture:ballistic":{"Schema":1,"Name":"Ballistic","Category":"COORDINATE_MISSILE","CoordinateProfile":"ballistics:srbm","Capacity":1}
            },
            "Selections":{"left":"fixture:cruise","right":"fixture:ballistic"},
            "Fired":{"left":1,"right":0}
        }""").asJsonObject
        val snapshot = com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot.decode(json)!!
        assertFalse(snapshot.storePresent(snapshot.definition.mounts[0], 0))
        assertTrue(snapshot.storePresent(snapshot.definition.mounts[1], 0))
        assertFalse(snapshot.stores["fixture:cruise"]!!.visualOnly)
        assertEquals("Cruise missile · coordinates required", snapshot.stores["fixture:cruise"]!!.categoryLabel)
        json.getAsJsonObject("Stores").getAsJsonObject("fixture:cruise").remove("CoordinateProfile")
        assertNull(com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot.decode(json))
    }

    @Test fun `launch precedes a single consumption and replay observes cooldown`() {
        var used = 0; var lastFire: Long? = null; var revision = 0
        val events = mutableListOf<String>()
        fun fire(now: Long) = AircraftStoreLaunchTransaction.execute(used, 2, lastFire, now,
            launch = { events.add("launch"); assertEquals(revision, used) },
            commit = { events.add("commit"); used++; lastFire = now; revision++ })
        fire(100)
        assertEquals(listOf("launch", "commit"), events)
        assertThrows(IllegalArgumentException::class.java) { fire(100) }
        assertEquals(1, used); assertEquals(100L, lastFire); assertEquals(1, revision)
        fire(110)
        assertEquals(2, used); assertEquals(2, revision)
        assertThrows(IllegalArgumentException::class.java) { fire(120) }
        assertEquals(4, events.size)
    }

    @Test fun `cruise cadence spans primary secondary and switched pylons without consuming rejected targets`() {
        val used = IntArray(4)
        val assigned = BooleanArray(4) { true }
        var lastCruise: Long? = null
        var inserted = 0
        fun fire(slot: Int, now: Long, insertionSucceeds: Boolean = true) =
            AircraftStoreLaunchTransaction.execute(used[slot], 1, null, now, lastCruise,
                launch = {
                    require(insertionSucceeds) { "Entity insertion failed" }
                    assertTrue(assigned[slot])
                    assigned[slot] = false
                    inserted++
                }, commit = { used[slot]++; lastCruise = now })

        fire(0, 0)
        for (tick in listOf(0L, 1L, 9L)) {
            assertThrows(IllegalArgumentException::class.java) { fire(1, tick) }
            assertTrue(assigned[1])
            assertEquals(0, used[1])
        }
        fire(1, 10)
        assertThrows(IllegalArgumentException::class.java) { fire(2, 19) }
        assertThrows(IllegalArgumentException::class.java) { fire(2, 20, false) }
        assertEquals(10L, lastCruise)
        assertTrue(assigned[2])
        assertEquals(0, used[2])
        fire(3, 20)
        assertEquals(3, inserted)
        assertArrayEquals(intArrayOf(1, 1, 0, 1), used)
        assertEquals(20L, lastCruise)

        // Separate vehicles keep their own cadence; non-cruise stores omit the shared clock.
        var independent = false
        AircraftStoreLaunchTransaction.execute(0, 1, null, 20,
            launch = { independent = true }, commit = {})
        assertTrue(independent)
    }
}
