package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftArmamentReceiptCostTest {
    @Test fun thinSeekerAndLaserUpdatesRetainCatalogueIdentityAndIsolateChangedFields() {
        val cache = AircraftArmamentStateCache()
        val full = JsonParser.parseString("""{
          "Vehicle":"00000000-0000-0000-0000-000000000001","EntityId":1,"Revision":0,
          "Definition":{"Schema":1,"Name":"Test aircraft","BuiltInWeapons":[],"Pairs":[
            {"Id":"inner","Name":"Inner","Left":[-2,0,0],"Right":[2,0,0],
             "AllowedStores":["test:missile"],"StoreGroups":{}}],"StoreGroups":{}},
          "Stores":{"test:missile":{"Schema":1,"Name":"Missile","Category":"AIR_TO_AIR",
            "Item":"minecraft:arrow","Capacity":1}},"Selections":{"inner":"test:missile"},
          "Point":[10,20,30]
        }""").asJsonObject
        val original = cache.receive(full)!!
        val stores = original.json.get("Stores")
        val definition = original.json.get("Definition")
        assertNotSame(full.get("Stores"), stores, "untrusted full input must still be copied")
        repeat(1000) { index ->
            val seek = JsonObject().apply {
                addProperty("Revision", index + 1); addProperty("WeaponId", "AircraftStore:inner")
                addProperty("Status", "NO_TARGET")
            }
            val receipt = JsonObject().apply {
                addProperty("Vehicle", original.snapshot.vehicle.toString()); addProperty("EntityId", 1)
                add("Seek", seek)
            }
            val next = cache.receive(receipt)!!
            assertSame(stores, next.json.get("Stores"), "seeker update must not rebuild mounted stores")
            assertSame(definition, next.json.get("Definition"))
            seek.addProperty("Status", "BAD_INPUT_MUTATION")
            assertEquals("NO_TARGET", next.json.getAsJsonObject("Seek").get("Status").asString)
        }
        assertFalse(original.json.has("Seek"), "old receipt remains unchanged")
        val clear = JsonObject().apply {
            addProperty("Vehicle", original.snapshot.vehicle.toString()); addProperty("EntityId", 1)
            addProperty("PointRevision", 1); addProperty("ClearPoint", true)
        }
        val next = cache.receive(clear)!!
        assertSame(stores, next.json.get("Stores"))
        assertFalse(next.json.has("Point"))
        assertTrue(original.json.has("Point"))
    }
}
