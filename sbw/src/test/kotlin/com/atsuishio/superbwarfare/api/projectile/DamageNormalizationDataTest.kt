package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.tools.blast.BlastModel
import com.atsuishio.superbwarfare.tools.blast.VehicleDamageClass
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * The owner's damage rules (2026-09-28) hold in the generated BVP data (tools/damage/balance.py). Skipped when the
 * BVP sources are not beside this project.
 */
class DamageNormalizationDataTest {
    private val data = File(System.getProperty("bvp.root") ?: "../bvp", "src/generated/resources/data/berts_vehicle_pack")
    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }
    private fun vehicles() = File(data, "sbw/vehicles").listFiles { f -> f.name.endsWith(".json") }!!.associate {
        it.name.removeSuffix(".json") to json(it)
    }
    private fun combats(): List<Pair<String, JsonObject>> = File(data, "sbw/projectile_profiles").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".json") }
        .mapNotNull { f -> json(f).getAsJsonObject("Combat")?.let { f.path.substringAfter("projectile_profiles/") to it } }
        .toList()

    @Test fun `hull HP follows class and weight`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        val all = vehicles()
        val mbts = all.filter { it.value.get("DamageClass")?.asString == "MBT" }
        assertTrue(mbts.size >= 20)
        for ((name, v) in mbts) assertTrue(v.get("MaxHealth").asInt in 255..345, name)
        assertEquals(300.0, mbts.values.map { it.get("MaxHealth").asDouble }.average(), 30.0)
        for ((name, v) in all) {
            val cls = v.get("DamageClass")?.asString
            assertTrue(cls != null && runCatching { VehicleDamageClass.valueOf(cls) }.isSuccess, "$name class $cls")
            if (cls == "IFV") assertTrue(v.get("MaxHealth").asInt in 150..215, name)
            if (cls == "AIRPLANE") {
                val wings = v.getAsJsonArray("AircraftSurfaceModules") ?: continue
                for (w in wings) {
                    val id = w.asJsonObject.get("Id").asString
                    assertTrue(id.endsWith("wing_left") || id.endsWith("wing_right"), "$name: $id is hull")
                    assertEquals(0.4, w.asJsonObject.get("MaxHealthFraction").asDouble, 1e-9)
                }
            }
        }
        // aircraft HP grows with size
        assertTrue(all.getValue("b_1b").get("MaxHealth").asInt > 3 * all.getValue("f_16c").get("MaxHealth").asInt)
    }

    @Test fun `rounds follow the class rules`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        val r = 300.0
        var atgms = 0
        for ((path, c) in combats()) {
            val cls = c.get("HullDamageClass").asString
            val munition = c.get("MunitionType").asString.substringAfter(':')
            val cal = (c.get("CaliberMm") ?: c.get("DiameterMm"))?.asDouble ?: continue
            val hull = c.get("HullDamage").asDouble
            val module = c.get("ModuleDamage").asInt
            val rack = c.get("AmmoRackDamage").asInt
            if (path.startsWith("aircraft_stores/") || munition == "cluster_bomblet") continue
            assertTrue(rack in 1..1000, "$path rack per mille $rack")
            assertEquals(minOf(100.0, 0.6 * hull), module.toDouble(), 1.0, "$path module damage")
            when {
                cls == "ATGM" && munition == "atgm" -> {
                    atgms++
                    assertTrue(hull in 0.30 * r - 1..0.55 * r + 1, "$path ATGM $hull")
                }
                cls == "APFSDS" && munition == "tank_shell" && cal >= 120 ->
                    assertEquals(0.30 * r, hull, 0.06 * r, "$path APFSDS")
                cls == "HEAT_FS" && munition == "tank_shell" && cal >= 120 ->
                    assertEquals(0.40 * r, hull, 0.06 * r, "$path HEAT-FS")
                cls == "HE" && munition == "tank_shell" && cal >= 75 -> {
                    assertEquals(0.5 * cal, c.get("PenetrationMm").asDouble, 0.06, "$path HE penetration")
                    // twice the APFSDS of the calibre: a 125 mm HE shell all but destroys an IFV
                    assertEquals(0.60 * r * Math.pow(cal / 120.0, 0.75), hull, 1.0, "$path HE")
                    if (cal >= 120) assertTrue(hull >= 180.0, "$path HE is strong against an IFV")
                }
                munition == "bullet" && cal < 20 -> assertTrue(hull <= 5.0, "$path MG")
            }
        }
        assertTrue(atgms >= 30)
    }

    @Test fun `machine gun belts carry no charge`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        val combats = combats().toMap()
        fun walk(e: JsonElement, where: String, out: MutableList<String>) {
            if (e.isJsonObject) {
                val o = e.asJsonObject
                val ref = o.getAsJsonObject("NominalBallistics")?.get("ProjectileProfile")?.asString
                val cal = ref?.let { combats[it.substringAfter(':') + ".json"] }?.let {
                    (it.get("CaliberMm") ?: it.get("DiameterMm"))?.asDouble }
                if (cal != null && cal < 20.0 && (o.has("TntEquivalentKg") ||
                        (o.get("ExplosionDamage")?.asDouble ?: 0.0) != 0.0)) out += "$where ($cal mm)"
                for ((k, v) in o.entrySet()) walk(v, "$where/$k", out)
            } else if (e.isJsonArray) e.asJsonArray.forEachIndexed { i, v -> walk(v, "$where[$i]", out) }
        }
        val charged = ArrayList<String>()
        for ((name, v) in vehicles()) walk(v.get("Weapons") ?: continue, name, charged)
        assertTrue(charged.isEmpty(), charged.joinToString("\n"))
    }

    @Test fun `blast damage scales with class and falls off to the severe radius`() {
        val radii = BlastModel.radii(5.24)
        val mbt = BlastModel.classBlastDamage(5.24, 0.0, radii, VehicleDamageClass.MBT.blastMultiplier)
        assertEquals(15.72, mbt, 1e-9)
        assertEquals(2 * mbt, BlastModel.classBlastDamage(5.24, 0.0, radii, VehicleDamageClass.IFV.blastMultiplier), 1e-9)
        assertEquals(0.0, BlastModel.classBlastDamage(5.24, radii.severe, radii, 6.0), 1e-9)
        assertTrue(BlastModel.classBlastDamage(5.24, (radii.fireball + radii.severe) / 2, radii, 1.0) in 0.0..mbt)
        // no 25 kg cliff: a 1 kg charge still scratches a car
        assertTrue(BlastModel.classBlastDamage(1.0, 0.0, BlastModel.radii(1.0), VehicleDamageClass.CAR.blastMultiplier) > 0.0)
        // infantry peak grows with the charge
        assertEquals(1.0, BlastModel.infantryPeakScale(0.25), 1e-9)
        assertTrue(BlastModel.infantryPeakScale(117.0) > 7.0)
        assertTrue(abs(BlastModel.infantryPeakScale(0.05) - 0.585) < 0.01)
    }
}
