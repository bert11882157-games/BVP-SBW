package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Munition models (pylon stores, their flight models, ATGMs, projectiles) are wound outward for the mesh loader,
 * which mirrors X and takes each face normal as (p1 - p0) x (p2 - p0). An inside-out model has every normal facing
 * into the body and renders all dark under the stores' viewer-facing light (the owner's report, 2026-09-28).
 * tools/munition_models/fix_winding.py repairs one. Skipped when the pack data is not beside this project.
 */
class MunitionMeshWindingTest {
    private val root = File(System.getProperty("bvp.customGeo")
        ?: "../bvp/src/generated/resources/assets/berts_vehicle_pack/custom_geo")

    private fun meshes(node: JsonElement, out: MutableList<JsonObject>) {
        when {
            node.isJsonObject -> {
                val obj = node.asJsonObject
                obj.get("poly_mesh")?.takeIf { it.isJsonObject }?.let { out += it.asJsonObject }
                obj.entrySet().forEach { meshes(it.value, out) }
            }
            node.isJsonArray -> node.asJsonArray.forEach { meshes(it, out) }
        }
    }

    /** Six times the signed volume of the whole mesh with the loader's normals (positive = outward). */
    private fun signedVolume(mesh: JsonObject): Double {
        val positions = mesh.getAsJsonArray("positions") ?: return 0.0
        val points = positions.map { p -> p.asJsonArray.let { doubleArrayOf(-it[0].asDouble, it[1].asDouble, it[2].asDouble) } }
        var total = 0.0
        for (poly in mesh.getAsJsonArray("polys") ?: JsonArray()) {
            val idx = poly.asJsonArray.map { it.asJsonArray[0].asInt }
            if (idx.size < 3) continue
            val a = points[idx[0]]
            for (k in 1 until idx.size - 1) {
                val b = points[idx[k]]; val c = points[idx[k + 1]]
                total += a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) +
                    a[2] * (b[0] * c[1] - b[1] * c[0])
            }
        }
        return total
    }

    @Test fun `munition models are wound outward for the mesh loader`() {
        val dirs = listOf("aircraft_stores", "atgm", "projectiles").map { File(root, it) }.filter { it.isDirectory }
        assumeTrue(dirs.isNotEmpty(), "pack data not beside this project")
        val inverted = mutableListOf<String>()
        for (dir in dirs) for (file in dir.listFiles().orEmpty().filter { it.name.endsWith(".geo.json") }) {
            val found = mutableListOf<JsonObject>()
            meshes(JsonParser.parseString(file.readText()), found)
            val volume = found.sumOf { signedVolume(it) }
            if (volume < 0) inverted += "${dir.name}/${file.name}"
        }
        assertTrue(inverted.isEmpty(), "inside-out munition models (run tools/munition_models/fix_winding.py):\n" +
            inverted.joinToString("\n"))
    }
}
