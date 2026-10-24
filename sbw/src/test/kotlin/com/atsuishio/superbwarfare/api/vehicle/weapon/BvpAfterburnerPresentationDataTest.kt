package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every BVP AfterburnerPresentation is one BvpAircraftAfterburnerRenderer.compile accepts: Schema 2 (TaP) carries
 * 1..16 TapEmitters, a dry (non-afterburning) block is Schema 1. test8's client log showed "TaP afterburner requires
 * 1..16 source emitters" for h_6k, vautour_iin, vulcan_b2 and yak_38m, whose fits kept their afterburning
 * template's Schema 2 while dropping its emitters, so their exhaust presentation was skipped (2026-10-24).
 * Skipped when the BVP sources are not beside this project.
 */
class BvpAfterburnerPresentationDataTest {
    private val vehicles = File(System.getProperty("bvp.root") ?: "../bvp",
        "src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles")

    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }

    private fun problem(file: File): String? {
        val block = json(file).getAsJsonObject("AfterburnerPresentation") ?: return null
        val schema = block.get("Schema")?.asInt
        val emitters = block.getAsJsonArray("TapEmitters")?.size() ?: 0
        val afterburning = block.get("Afterburning")?.asBoolean ?: true
        val id = file.nameWithoutExtension
        return when {
            schema == 2 && emitters !in 1..16 -> "$id: Schema 2 with $emitters TapEmitters"
            schema == 2 && !afterburning -> "$id: dry block on Schema 2"
            else -> null
        }
    }

    @Test fun `the four dry expansion jets are Schema 1`() {
        assumeTrue(vehicles.isDirectory)
        for (id in listOf("h_6k", "vautour_iin", "vulcan_b2", "yak_38m")) {
            val block = json(File(vehicles, "$id.json")).getAsJsonObject("AfterburnerPresentation")
            assertEquals(1, block.get("Schema").asInt, id)
        }
    }

    @Test fun `every afterburner presentation in the pack compiles`() {
        assumeTrue(vehicles.isDirectory)
        val all = vehicles.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.mapNotNull(::problem)
        assertEquals(emptyList<String>(), all)
    }
}
