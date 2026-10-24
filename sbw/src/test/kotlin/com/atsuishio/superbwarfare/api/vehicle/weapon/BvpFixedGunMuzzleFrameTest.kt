package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every BVP fixed-gun-bank weapon fired from the Vehicle frame shoots from exactly its muzzle attachment's frame
 * (the bank refuses the gun otherwise: "muzzle ... differs from its attachment frame"; test7-r3 found f_22a Vulcan,
 * mirage_3e Gun1/Gun2 and yak_141 Cannon still on their templates' shoot positions, 2026-10-24).
 * Skipped when the BVP sources are not beside this project.
 */
class BvpFixedGunMuzzleFrameTest {
    private val vehicles = File(System.getProperty("bvp.root") ?: "../bvp",
        "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles")

    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }
    private fun JsonArray.doubles() = map { it.asDouble }

    /** "<vehicle> <weapon> <muzzle>: <reason>" for every bank gun whose shoot position and muzzle frame disagree */
    private fun mismatches(file: File): List<String> {
        val data = json(file)
        val attachments = data.getAsJsonObject("Attachments") ?: JsonObject()
        val banked = data.getAsJsonArray("Seats")?.flatMap { seat ->
            seat.asJsonObject.getAsJsonArray("FixedGunBank")?.map { it.asString } ?: emptyList()
        }?.toSet() ?: emptySet()
        val weapons = data.getAsJsonObject("Weapons") ?: return emptyList()
        val id = file.nameWithoutExtension
        return banked.flatMap { name ->
            val shoot = weapons.getAsJsonObject(name)?.getAsJsonObject("ShootPos") ?: return@flatMap emptyList()
            if (shoot.get("Transform")?.asString != "Vehicle") return@flatMap emptyList()
            val positions = shoot.getAsJsonArray("Positions")
            shoot.getAsJsonArray("MuzzleAttachments").mapIndexedNotNull { index, muzzle ->
                val frame = attachments.getAsJsonObject(muzzle.asString)
                when {
                    frame == null -> "$id $name ${muzzle.asString}: no attachment"
                    frame.get("Parent")?.asString != "Vehicle" -> "$id $name ${muzzle.asString}: parent ${frame.get("Parent")}"
                    frame.getAsJsonArray("Position").doubles() != positions[index].asJsonArray.doubles() ->
                        "$id $name ${muzzle.asString}: shoot ${positions[index]} vs attachment ${frame.get("Position")}"
                    else -> null
                }
            }
        }
    }

    @Test fun `the three test7-r3 jets shoot from their own muzzle frames`() {
        assumeTrue(vehicles.isDirectory)
        for (id in listOf("f_22a", "mirage_3e", "yak_141")) {
            val file = File(vehicles, "$id.json")
            assertTrue(file.isFile, "$id data")
            assertEquals(emptyList<String>(), mismatches(file))
        }
    }

    @Test fun `every banked vehicle-frame gun in the pack shoots from its muzzle frame`() {
        assumeTrue(vehicles.isDirectory)
        val all = vehicles.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.flatMap(::mismatches)
        assertEquals(emptyList<String>(), all)
    }
}
