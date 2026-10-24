package com.atsuishio.superbwarfare.data.vehicle.subdata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Offline spawn-reject test. DefaultVehicleData throws "AircraftTerrainContact requires two finite nonempty boxes" when
 * [AircraftTerrainContact.valid] is false, and valid() caps BodyParts at 16 (0.11.185beta: vulcan_b2 31, h_6k 45 and vautour_iin 23
 * did not spawn). Every aircraft and helicopter data file of the pack and of the built-in SBW fleet is decoded the way the data loader
 * does (ignoreUnknownKeys) and must be valid. The BVP check is skipped when the BVP sources are not beside this project.
 */
class AircraftTerrainContactDataTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val bvp = File(System.getProperty("bvp.root") ?: "../bvp")
    private val directories = listOf(
        File(bvp, "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles"),
        File("src/main/resources/assets/superbwarfare/sbw/vehicles"),
    )

    private fun contacts(): Map<String, AircraftTerrainContact> {
        val out = LinkedHashMap<String, AircraftTerrainContact>()
        for (directory in directories.filter { it.isDirectory }) {
            for (file in directory.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
                val root = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
                val block = root["AircraftTerrainContact"] ?: continue
                out[directory.parentFile.parentFile.name + "/" + file.name.removeSuffix(".json")] =
                    json.decodeFromString(AircraftTerrainContact.serializer(), block.toString())
            }
        }
        return out
    }

    @Test fun `every aircraft and helicopter terrain contact is accepted at spawn`() {
        assumeTrue(directories.any { it.isDirectory }, "vehicle data not present")
        val contacts = contacts()
        assertTrue(contacts.size >= 90, "expected the whole air fleet, found ${contacts.size}")
        val rejected = contacts.filter { !it.value.valid() }.map { (id, c) ->
            "$id: ${c.bodyParts.size} BodyParts, fuselage ${c.fuselage.valid()}, gear ${c.landingGear.valid()}, " +
                "parts ${c.bodyParts.all { it.valid() }}, wreck ${c.wreckSections.size}, wheels ${c.validWheelContacts()}"
        }
        assertTrue(rejected.isEmpty(), "spawn would throw 'AircraftTerrainContact requires two finite nonempty boxes' for:\n" +
            rejected.joinToString("\n"))
    }

    @Test fun `the three bombers that failed to spawn in 0_11_185beta are in the fleet and valid`() {
        assumeTrue(directories[0].isDirectory, "BVP sources not present")
        val contacts = contacts().filterKeys { it.startsWith("berts_vehicle_pack/") }
        for (id in listOf("vulcan_b2", "h_6k", "vautour_iin", "b_47e")) {
            val contact = contacts["berts_vehicle_pack/$id"] ?: error("$id has no AircraftTerrainContact")
            assertTrue(contact.bodyParts.size in 1..16, "$id has ${contact.bodyParts.size} BodyParts")
            assertTrue(contact.valid(), id)
        }
    }

    @Test fun `valid rejects more than 16 body parts, degenerate boxes and bad wreck sections`() {
        fun box(x: Double) = AircraftTerrainBox().apply {
            minimum = net.minecraft.world.phys.Vec3(x, 0.0, 0.0); maximum = net.minecraft.world.phys.Vec3(x + 1.0, 1.0, 1.0)
        }
        val base = AircraftTerrainContact().apply {
            fuselage = box(0.0); landingGear = box(0.0)
            bodyParts = (0 until 16).map { box(it.toDouble()) }
        }
        assertTrue(base.valid())
        assertFalse(AircraftTerrainContact().valid(), "default boxes are empty")
        base.bodyParts = (0 until 17).map { box(it.toDouble()) }
        assertFalse(base.valid(), "17 body parts")
        base.bodyParts = listOf(AircraftTerrainBox())
        assertFalse(base.valid(), "an empty box")
        base.bodyParts = listOf(box(0.0))
        base.wreckSections = listOf(box(0.0))
        assertFalse(base.valid(), "wreck sections must be 0 or 4")
        base.wreckSections = (0 until 4).map { box(it.toDouble()) }
        assertEquals(true, base.valid())
    }
}
