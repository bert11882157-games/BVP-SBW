package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Integration coverage of the pack's maintained resource overlay through the real SBW schema. */
class BvpCoordinateResourcesTest {
    private val resources = Path.of("..", "bvp", "src", "main", "resources")
    private fun read(path: String): JsonObject = Files.newBufferedReader(resources.resolve(path)).use {
        JsonParser.parseReader(it).asJsonObject
    }

    @Test fun `Tu95 has eight independent Kh55 mounts backed by valid coordinate store data`() {
        val store = read("data/berts_vehicle_pack/sbw/aircraft_stores/kh55.json")
        val aircraft = read("data/berts_vehicle_pack/sbw/aircraft_armaments/tu_95ms.json")
        AircraftArmamentRegistry.validate(store, true)
        AircraftArmamentRegistry.validate(aircraft, false)
        assertEquals("CRUISE_MISSILE", store["Category"].asString)
        assertEquals("ballistics:kh55", AircraftCoordinateLauncher.profile(store))
        assertEquals("berts_vehicle_pack:custom_geo/aircraft_stores/kh29l.geo.json", store["Model"].asString)
        val mounts = AircraftArmamentRegistry.mounts(aircraft)
        assertEquals((1..8).map { "belly_$it" }.toSet(), mounts.map { it["Id"].asString }.toSet())
        for (mount in mounts) {
            assertEquals(1, AircraftArmamentRegistry.mountCapacity(mount, store["Capacity"].asInt))
            assertEquals(1, AircraftArmamentRegistry.mountPositions(mount).size)
            assertEquals(listOf("berts_vehicle_pack:kh55"), mount.getAsJsonArray("AllowedStores").map { it.asString })
        }
        assertEquals(8, mounts.map { AircraftArmamentRegistry.launchPosition(it, 0) }.distinct().size)
        for (key in listOf("Model", "Texture")) {
            val id = store[key].asString.split(":", limit = 2)
            assertTrue(Files.isRegularFile(resources.resolve("assets/${id[0]}/${id[1]}")) ||
                Files.isRegularFile(Path.of("..", "bvp", "src", "generated", "resources",
                    "assets", id[0], id[1])), "Missing referenced $key")
        }
    }
}
