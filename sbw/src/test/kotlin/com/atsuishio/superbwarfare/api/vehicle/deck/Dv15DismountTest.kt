package com.atsuishio.superbwarfare.api.vehicle.deck

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The DV15 (BVP boat, tools/vehgen/dv15_build.py) lets every seat out beside the hull at the seat's own z. The first build gave all
 * four seats one point 1 block behind the stern (the helm 11 blocks from it): SBW's VehicleEntity.getDismountLocationForIndex only
 * transforms the DismountInfo Position by the vehicle pose (no velocity offset, no hull awareness), so that was a long teleport,
 * the defect behind the LCAC's 23-block throw. Skipped when the BVP sources are not beside this project.
 */
class Dv15DismountTest {
    private val file = File(System.getProperty("bvp.root") ?: "../bvp",
        "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/dv15.json")

    private fun vec(a: com.google.gson.JsonArray) = a.map { it.asDouble }

    @Test
    fun everySeatDismountsBesideTheHullWithinTwoBlocks() {
        assumeTrue(file.isFile, "BVP sources not present")
        val json: JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }
        val seats = json.getAsJsonArray("Seats")
        assertEquals(4, seats.size())
        // the collision boxes in the vehicle frame (the station boxes ride the turret and barrel frames above the hull)
        val boxes = json.getAsJsonArray("OBB").map { it.asJsonObject }
            .filter { (it.get("Transform")?.asString ?: "Vehicle") == "Vehicle" }
        assertEquals(4, boxes.size, "the hull is four slices")
        fun inside(p: List<Double>): Boolean = boxes.any { o ->
            val c = vec(o.getAsJsonArray("Position")); val h = vec(o.getAsJsonArray("Size"))
            abs(p[0] - c[0]) < h[0] && abs(p[1] - c[1]) < h[1] && abs(p[2] - c[2]) < h[2]
        }
        val random = Random(9)
        for ((n, e) in seats.withIndex()) {
            val seat = e.asJsonObject
            val info = seat.getAsJsonObject("DismountInfo")
            assertEquals("Vehicle", info.get("Transform").asString, "seat $n frame")
            val s = vec(seat.getAsJsonArray("Position"))
            val p = vec(info.getAsJsonArray("Position"))
            val horizontal = Math.hypot(p[0] - s[0], p[2] - s[2])
            assertTrue(horizontal <= 2.0, "seat $n dismount ${"%.2f".format(horizontal)} blocks from the seat")
            assertTrue(!inside(p), "seat $n dismount $p is inside a hull box")
            // beside the hull on the seat's own side, outside the hull footprint at that z with half a block to spare
            assertTrue(p[0] * s[0] > 0.0, "seat $n dismount on the wrong side")
            for (o in boxes) {
                val c = vec(o.getAsJsonArray("Position")); val h = vec(o.getAsJsonArray("Size"))
                if (abs(p[2] - c[2]) <= h[2]) assertTrue(abs(p[0] - c[0]) >= h[0] + 0.4, "seat $n clearance of a hull slice")
            }
            repeat(50) {
                val pose = DeckPose(random.nextDouble(-900.0, 900.0), 63.0, random.nextDouble(-900.0, 900.0),
                    random.nextFloat() * 360f - 180f)
                val d = Math.hypot(pose.worldX(p[0], p[2]) - pose.worldX(s[0], s[2]),
                    pose.worldZ(p[0], p[2]) - pose.worldZ(s[0], s[2]))
                assertTrue(d <= 2.0, "seat $n world distance ${"%.2f".format(d)} at yaw ${pose.yaw}")
            }
        }
    }
}