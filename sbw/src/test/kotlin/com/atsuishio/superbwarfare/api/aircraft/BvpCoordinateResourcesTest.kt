package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftCountermeasureDefinition
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot
import kotlinx.serialization.json.Json
import net.minecraft.world.phys.Vec3
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

    @Test fun `Tu95 expends 128 individual flares before its existing automatic reload`() {
        val vehicle = read("data/berts_vehicle_pack/sbw/vehicles/tu_95ms.json")
        val definition = Json.decodeFromString<AircraftCountermeasureDefinition>(
            vehicle.getAsJsonObject("Countermeasures").toString())
        assertEquals(128, definition.flaresPerBurst)
        assertEquals(20, definition.flaresPerSecond)
        assertTrue(definition.flares)
        assertTrue(definition.chaff)
        val state = AircraftCountermeasureState()
        fun sample(tick: Long) = state.tick(tick, true, false, definition.flares,
            definition.chaff, definition.flaresPerSecond, definition.flaresPerBurst)
        val emissions = mutableListOf<Long>()
        for (tick in 0L..126L) {
            val output = sample(tick)
            repeat(output.flarePairs) { emissions += tick }
            assertEquals(if (tick == 126L) 400 else 0, output.flareCooldown)
        }
        assertEquals((0L..126L step 2).toList(), emissions)
        assertEquals(128, emissions.size * 2, "capacity counts flares, not paired releases")
        for (tick in 127L..525L) assertEquals(0, sample(tick).flarePairs)
        assertEquals(1, sample(526).flarePairs)
        assertEquals(2, state.burstExpenditure())
    }

    @Test fun `Tu95 has eight independent Kh55 mounts backed by valid coordinate store data`() {
        val store = read("data/berts_vehicle_pack/sbw/aircraft_stores/kh55.json")
        val aircraft = read("data/berts_vehicle_pack/sbw/aircraft_armaments/tu_95ms.json")
        AircraftArmamentRegistry.validate(store, true)
        AircraftArmamentRegistry.validate(aircraft, false)
        assertEquals("CRUISE_MISSILE", store["Category"].asString)
        assertEquals("ballistics:kh55", AircraftCoordinateLauncher.profile(store))
        assertEquals("berts_vehicle_pack:custom_geo/aircraft_stores/kh55.geo.json", store["Model"].asString)
        val mounts = AircraftArmamentRegistry.mounts(aircraft)
        assertEquals((1..8).map { "belly_$it" }.toSet(), mounts.map { it["Id"].asString }.toSet())
        for ((index, mount) in mounts.withIndex()) {
            assertEquals("Bay ${index + 1}", mount["Name"].asString)
            assertEquals(1, AircraftArmamentRegistry.mountCapacity(mount, store["Capacity"].asInt))
            assertEquals(1, AircraftArmamentRegistry.mountPositions(mount).size)
            assertEquals(listOf("berts_vehicle_pack:kh55"), mount.getAsJsonArray("AllowedStores").map { it.asString })
        }
        assertEquals(8, mounts.map { AircraftArmamentRegistry.launchPosition(it, 0) }.distinct().size)
        val group = AircraftStoreWeapons.collect(mounts.map { mount ->
            AircraftStoreWeapons.Equipped("berts_vehicle_pack:kh55", store,
                AircraftStoreWeapons.Member(mount["Id"].asString, emptyList(), 1, 1))
        }) { null }.single()
        assertEquals("AircraftStoreGroup:berts_vehicle_pack:kh55", group.weaponId)
        assertEquals(8, group.ammo)
        assertEquals(8, group.capacity)
        assertEquals("belly_1", group.next!!.mountId)
        for (key in listOf("Model", "Texture")) {
            val id = store[key].asString.split(":", limit = 2)
            assertTrue(Files.isRegularFile(resources.resolve("assets/${id[0]}/${id[1]}")) ||
                Files.isRegularFile(Path.of("..", "bvp", "src", "generated", "resources",
                    "assets", id[0], id[1])), "Missing referenced $key")
        }
    }

    @Test fun `Tu95 internal stations release the supplied missile below the central fuselage`() {
        val storeId = "berts_vehicle_pack:kh55"
        val store = read("data/berts_vehicle_pack/sbw/aircraft_stores/kh55.json")
        val aircraft = read("data/berts_vehicle_pack/sbw/aircraft_armaments/tu_95ms.json")
        val vehicle = read("data/berts_vehicle_pack/sbw/vehicles/tu_95ms.json")
        val mounts = AircraftArmamentRegistry.mounts(aircraft)
        val receipt = JsonObject().apply {
            addProperty("Vehicle", "00000000-0000-0000-0000-000000000001")
            addProperty("EntityId", 1)
            addProperty("Revision", 1)
            add("Definition", aircraft)
            add("Stores", JsonObject().apply { add(storeId, store) })
            add("Selections", JsonObject().apply {
                mounts.forEach { addProperty(it["Id"].asString, storeId) }
            })
        }
        val snapshot = AircraftArmamentSnapshot.decode(receipt)!!
        for (mount in snapshot.definition.mounts) {
            // The suspended-store renderer uses key presence to select authored airframe bones
            // instead of drawing an external mesh. Empty bindings keep these payloads internal.
            assertTrue(mount.groups.containsKey(storeId))
            assertTrue(mount.groups.getValue(storeId).isEmpty())
        }
        assertTrue(snapshot.visibleBones().isEmpty())
        assertTrue(snapshot.allStoreBones().isEmpty())

        fun points(path: String) = read(path).getAsJsonArray("minecraft:geometry")[0]
            .asJsonObject.getAsJsonArray("bones").flatMap { bone ->
                bone.asJsonObject.getAsJsonObject("poly_mesh")?.getAsJsonArray("positions")
                    ?.map { AircraftArmamentRegistry.vector(it)!!.scale(1.0 / 16.0) }.orEmpty()
            }
        val storePoints = points("assets/berts_vehicle_pack/custom_geo/aircraft_stores/kh55.geo.json")
        val flightPoints = points("assets/berts_vehicle_pack/custom_geo/projectiles/kh55.geo.json")
        val halfWidth = storePoints.maxOf { kotlin.math.abs(it.x) }
        val visualTop = storePoints.maxOf { it.y }
        val halfLength = storePoints.maxOf { kotlin.math.abs(it.z) }
        val flightLength = flightPoints.maxOf { it.y }
        val offset = AircraftArmamentRegistry.vector(store["LaunchOffset"])!!
        assertEquals(1.0, store["Scale"].asDouble)
        assertEquals(0.0, flightPoints.minOf { it.y }, 1e-6)
        assertEquals(5.95, flightLength, 1e-6)
        assertEquals(flightLength, 2 * halfLength, 1e-6)
        assertEquals(-halfLength, offset.z, 1e-6, "launch position is the rendered nozzle")
        assertEquals(Vec3(0.0, 0.0, 1.0), AircraftArmamentRegistry.vector(store["LaunchDirection"]))
        for (mount in mounts) {
            val rack = AircraftArmamentRegistry.launchPosition(mount, 0)
            assertTrue(kotlin.math.abs(rack.x) <= .56)
            assertTrue(rack.y in 3.4..4.6)
            assertEquals(-7.8, rack.z, 1e-6)
            val origin = rack.add(offset)
            val hull = vehicle.getAsJsonArray("OBB").map { it.asJsonObject }.filter {
                val center = AircraftArmamentRegistry.vector(it["Position"])!!
                val size = AircraftArmamentRegistry.vector(it["Size"])!!
                center.x + size.x >= origin.x - halfWidth && center.x - size.x <= origin.x + halfWidth &&
                    center.z + size.z >= origin.z && center.z - size.z <= origin.z + flightLength
            }
            assertTrue(hull.isNotEmpty(), "release must be below the aircraft's central body")
            val underside = hull.minOf {
                AircraftArmamentRegistry.vector(it["Position"])!!.y -
                    AircraftArmamentRegistry.vector(it["Size"])!!.y
            }
            assertTrue(origin.y + maxOf(visualTop, .35) < underside - .1,
                "${mount["Name"]}: visual body and missile collision shoulder must clear the fuselage")
        }
    }
}
