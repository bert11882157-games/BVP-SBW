package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftArmamentReceiptCacheTest {
    private fun full() = JsonParser.parseString("""{
        "Vehicle":"00000000-0000-0000-0000-000000000001","EntityId":1,"Revision":1,
        "CatalogueRevision":1,"PointRevision":0,
        "Definition":{"Schema":1,"Name":"Plane","Singles":[{"Id":"bay","Name":"Bay",
            "Position":[0,0,0],"AllowedStores":["test:kh55"]}]},
        "Stores":{"test:kh55":{"Schema":1,"Name":"KH-55","Category":"CRUISE_MISSILE",
            "CoordinateProfile":"test:cruise","Capacity":1}},"Selections":{"bay":"test:kh55"}
    }""").asJsonObject

    @Test fun `thin point and seeker updates preserve catalogue identity and previous receipts`() {
        val cache = AircraftArmamentStateCache()
        val input = full()
        val initial = cache.receive(input)!!
        input.getAsJsonObject("Definition").addProperty("Name", "mutated sender")
        assertEquals("Plane", initial.json.getAsJsonObject("Definition")["Name"].asString)
        val point = JsonParser.parseString("""{"Vehicle":"${initial.snapshot.vehicle}","EntityId":1,
            "PointRevision":1,"Point":[100,60,200]}""").asJsonObject
        val updated = cache.receive(point)!!
        assertSame(initial.json["Definition"], updated.json["Definition"])
        assertSame(initial.json["Stores"], updated.json["Stores"])
        assertNull(initial.snapshot.point)
        assertFalse(initial.json.has("Point"))
        point.getAsJsonArray("Point").set(0, com.google.gson.JsonPrimitive(999))
        assertEquals(100.0, updated.json.getAsJsonArray("Point")[0].asDouble)
        val seek = JsonParser.parseString("""{"Vehicle":"${initial.snapshot.vehicle}","EntityId":1,
            "Seek":{"Revision":1,"WeaponId":"","Status":"NO_TARGET","Ready":false,"Progress":0}}
        """).asJsonObject
        val seeking = cache.receive(seek)!!
        assertSame(updated.json["Definition"], seeking.json["Definition"])
        assertSame(updated.json["Stores"], seeking.json["Stores"])
        assertFalse(updated.json.has("Seek"))
        assertNull(cache.receive(seek), "replayed seek rejected")
        assertSame(seeking, cache.get(initial.snapshot.vehicle))
        val replacement = full().apply { addProperty("CatalogueRevision", 2) }
        assertNotSame(seeking.json["Definition"], cache.receive(replacement)!!.json["Definition"])
        cache.clear()
        assertNull(cache.get(initial.snapshot.vehicle))
    }
}
