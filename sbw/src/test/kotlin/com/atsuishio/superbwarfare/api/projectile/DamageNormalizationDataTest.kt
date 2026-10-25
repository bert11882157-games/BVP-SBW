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
 * The owner's damage rules and reviewed later contracts hold in effective, reachable BVP data. Skipped when the
 * BVP sources are not beside this project.
 */
class DamageNormalizationDataTest {
    private companion object {
        /** Owner 2026-10-07: every missile's damage x0.75 (rounded to the nearest 5); module damage still follows hull. */
        const val MISSILE_SCALE = 0.75
    }
    private val bvp = File(System.getProperty("bvp.root") ?: "../bvp")
    private val data = File(bvp, "src/generated/resources/data/berts_vehicle_pack")
    private val resources by lazy { EffectiveDamageResources(bvp) }
    private fun vehicles() = resources.vehicles
    private fun combats() = resources.combats()

    // Reviewed current authored contracts, not blanket balance exemptions. AA20/AS20:74ae912b;
    // APKWS:6c0bae76; AS30:65d69368; Kh23M:411a832a (munition_models authoring).
    private val smallOrCommandStores = mapOf(
        "aircraft_stores/aa20.json" to listOf(140.0,84.0,250.0,20.0),
        "aircraft_stores/as20.json" to listOf(212.0,128.0,250.0,24.0),
        "aircraft_stores/as30.json" to listOf(1330.0,800.0,800.0,150.0),
        "aircraft_stores/kh23m.json" to listOf(1330.0,800.0,600.0,150.0),
        "aircraft_stores/apkws.json" to listOf(60.0,40.0,40.0,100.0),
    )
    private fun exactCombat(path: String, combat: JsonObject, expected: List<Double>) {
        listOf("HullDamage", "ModuleDamage", "PenetrationMm", "AmmoRackDamage").zip(expected).forEach { (key,value) ->
            assertEquals(value, combat.get(key).asDouble, 1e-9, "$path $key reviewed authored contract")
        }
    }

    @Test fun `live scope excludes retired resources and includes reviewed later contracts`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        assertTrue(vehicles().size >= 150, "registry parsing must not empty the test scope")
        assertTrue(combats().size >= 500, "reference traversal must not empty the test scope")
        assertTrue(setOf("lav_iii", "mbt_70", "type_89_ifv").all { it in resources.orphanVehicles })
        assertTrue(setOf("lav_iii", "bm_21_grad", "2s25_sprut_sd").none { it in vehicles() })
        val live = combats().toMap()
        assertTrue(live.keys.none { it.startsWith("bm_21_grad/") || it.startsWith("2s25_sprut_sd/") })
        for ((path, expected) in smallOrCommandStores) {
            val combat = live.getValue(path) // Missing references/renames require explicit review.
            assertEquals("HE", combat.get("HullDamageClass").asString, path)
            assertEquals("berts_vehicle_pack:atgm", combat.get("MunitionType").asString, path)
            exactCombat(path, combat, expected)
        }
        val m61 = live.getValue("aircraft_rounds/m61a1/m61_saphei.json")
        assertEquals("HE", m61.get("HullDamageClass").asString)
        assertEquals(20.0, m61.get("CaliberMm").asDouble)
        exactCombat("m61_saphei", m61, listOf(10.0,8.0,20.0,40.0))
        val apkws = live.getValue("aircraft_stores/apkws_m151.json")
        assertEquals("ATGM", apkws.get("HullDamageClass").asString)
        assertEquals(70.0, apkws.get("CaliberMm").asDouble)
        exactCombat("apkws_m151", apkws, listOf(60.0,36.0,50.0,276.0))
        println("Damage scope: ${vehicles().size} registered vehicles, ${live.size} referenced Combat profiles; " +
                "retired/unregistered vehicle resources: ${resources.orphanVehicles.sorted()}")
    }

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
            assertTrue(hull.isFinite() && hull > 0.0, "$path finite positive hull damage")
            assertTrue(module > 0, "$path positive module damage")
            assertTrue(rack in 1..1000, "$path rack per mille $rack")
            if ((path.startsWith("aircraft_stores/") && cls != "ATGM") || munition == "cluster_bomblet") continue
            if (path == "aircraft_rounds/m61a1/m61_saphei.json") {
                // 99ab8a94: the actively referenced M61 belt deliberately authors hull10/module8.
                exactCombat(path, c, listOf(10.0, 8.0, 20.0, 40.0))
            } else assertEquals(minOf(100.0, 0.6 * hull), module.toDouble(), 1.0, "$path module damage")
            when {
                cls == "ATGM" && munition == "atgm" -> {
                    atgms++
                    // owner 2026-10-07: all missile damage x0.75, rounded to the nearest 5
                    if (path == "aircraft_stores/apkws_m151.json") {
                        // 25eed3ca:70mm guided HE rocket, not a full-size anti-tank warhead.
                        exactCombat(path, c, listOf(60.0,36.0,50.0,276.0))
                    } else assertTrue(hull in MISSILE_SCALE * 0.30 * r - 5..MISSILE_SCALE * 0.55 * r + 5, "$path ATGM $hull")
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
                // owner 2026-09-29: autocannon HE is hit or miss against IFVs - it gets through thin weak spots
                cls == "HE" && munition == "autocannon_shell" && cal in 20.0..56.9 &&
                    !path.contains("grenadelauncher") && !path.contains("ags_30") ->
                    assertTrue(c.get("PenetrationMm").asDouble >= 0.7 * cal - 0.06, "$path autocannon HE penetration")
                munition == "bullet" && cal < 20 -> assertTrue(hull <= 5.0, "$path MG")
            }
        }
        assertTrue(atgms >= 30)
    }

    /**
     * Owner 2026-09-29 ("munitions underperforming"): every heavy air-to-ground missile carries a penetration, so the
     * armor classifier types it and its authored HullDamage applies (with 0 it fell back to a 120-damage ATGM); the
     * Hydra 70 M247 and S-5K are HEAT rockets.
     */
    @Test fun `heavy missiles and rockets carry typed penetration`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        var heavy = 0
        for ((path, c) in combats()) {
            val munition = c.get("MunitionType").asString.substringAfter(':')
            val pen = c.get("PenetrationMm").asDouble
            if (path.startsWith("aircraft_stores/") && munition == "atgm" && c.get("HullDamageClass").asString != "ATGM") {
                val reviewed = smallOrCommandStores[path]
                if (reviewed != null) {
                    assertEquals("HE", c.get("HullDamageClass").asString, path)
                    exactCombat(path, c, reviewed)
                    continue
                }
                heavy++
                assertTrue(pen >= 1000.0, "$path heavy missile penetration $pen")
                assertTrue(c.get("HullDamage").asDouble >= MISSILE_SCALE * 1000.0, "$path heavy missile hull damage")
            }
            if (munition == "rocket") assertTrue(pen > 0.0, "$path rocket penetration")
            if (c.get("RoundId").asString.endsWith(":hydra_70_m247")) {
                assertEquals("HEAT", c.get("HullDamageClass").asString, path)
                assertTrue(pen >= 250.0, "$path Hydra M247 penetration $pen")
            }
            if (c.get("RoundId").asString.endsWith(":s5k"))
                assertEquals("berts_vehicle_pack:chemical", c.get("DamageType").asString, path)
        }
        assertTrue(heavy >= 8, "heavy missiles found: $heavy")
    }

    /**
     * Owner 2026-09-30: cluster bombs open 15 m up with their real loads; every submunition is a 200 mm BVP HEAT
     * charge dealing a 100 mm HEAT cannon round's damage, with its real explosive fill.
     */
    @Test fun `cluster bombs carry their real loads of 200 mm HEAT submunitions`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        val stores = "sbw/aircraft_stores"
        val expected = mapOf(
            "munition/cbu87.json" to Pair(202, 0.4018), "munition/cbu99_rockeye_ii.json" to Pair(247, 0.2445),
            "rbk_250.json" to Pair(42, 0.585), "munition/cbu97.json" to Pair(10, 6.0102))
        val gunHeat = 0.40 * 300.0 * Math.pow(100.0 / 120.0, 0.75)
        for ((file, load) in expected) {
            val cluster = resources.json("$stores/$file").getAsJsonObject("Bomb").getAsJsonObject("Cluster")
            assertEquals(load.first, cluster.get("Count").asInt, "$file count")
            assertEquals(15.0, cluster.get("ReleaseHeight").asDouble, 1e-9, "$file opening height")
            assertEquals(load.second, cluster.get("BombletTntEquivalentKg").asDouble, 1e-4, "$file bomblet charge")
            val profile = (cluster.get("BombletProfile") ?: cluster.get("SensorProjectileProfile")).asString
            val c = resources.json("sbw/projectile_profiles/" + profile.substringAfter(':') + ".json")
                .getAsJsonObject("Combat")
            assertEquals(200.0, c.get("PenetrationMm").asDouble, 1e-9, "$profile penetration")
            assertEquals(gunHeat, c.get("HullDamage").asDouble, 1.0, "$profile hull damage")
        }
    }

    @Test fun `machine gun belts carry no charge`() {
        assumeTrue(data.isDirectory, "BVP sources not present")
        val combats = combats().toMap()
        var resolvedProfiles = 0
        fun walk(e: JsonElement, where: String, out: MutableList<String>) {
            if (e.isJsonObject) {
                val o = e.asJsonObject
                val ref = o.getAsJsonObject("NominalBallistics")?.get("ProjectileProfile")?.asString
                val combat = ref?.takeIf { it.startsWith("berts_vehicle_pack:") }?.let {
                    val key = it.substringAfter(':') + ".json"
                    assertTrue(combats.containsKey(key), "$where unresolved profile $key")
                    resolvedProfiles++
                    combats.getValue(key)
                }
                val cal = combat?.let { (it.get("CaliberMm") ?: it.get("DiameterMm"))?.asDouble }
                if (cal != null && cal < 20.0 && !EffectiveDamageResources.noCharge(o)) out += "$where ($cal mm)"
                for ((k, v) in o.entrySet()) walk(v, "$where/$k", out)
            } else if (e.isJsonArray) e.asJsonArray.forEachIndexed { i, v -> walk(v, "$where[$i]", out) }
        }
        val charged = ArrayList<String>()
        for ((name, v) in vehicles()) walk(v.get("Weapons") ?: continue, name, charged)
        assertTrue(resolvedProfiles >= 100, "profile lookup must not pass vacuously: $resolvedProfiles")
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
