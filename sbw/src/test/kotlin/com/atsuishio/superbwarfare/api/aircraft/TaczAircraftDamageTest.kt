package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.compat.tacz.TaczAircraftDamage
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class TaczAircraftDamageTest {
    private val fiftyCalibreIds = setOf(
        "tacz:50bmg", "tacz:50ae", "tacz:500mag", "ea:50gi", "ea:50beowulf",
        "ea:127x55", "ea:127x108", "suffuse:12.7x55", "suffuse:12.7x108mm",
        "sbw_flans:50_action_express",
    )

    @Test
    fun `all explicit installed fifty caliber identities use the fifty calibre damage`() {
        for (id in fiftyCalibreIds) {
            assertEquals(2.5f, TaczAircraftDamage.damageForAmmo(ResourceLocation(id), 130f), id)
        }
    }

    @Test
    fun `RPG rounds have aircraft direct damage without changing other TacZ ammunition`() {
        for (id in listOf("tacz:rpg_rocket", "sbw_flans:pg7v")) {
            assertEquals(91f, TaczAircraftDamage.damageForAmmo(ResourceLocation(id), 130f), 0.0001f, id)
        }
        for (id in listOf("tacz:9mm", "tacz:556x45", "tacz:762x39", "tacz:12g", "tacz:40mm",
            "sbw_flans:23x75_buckshot", "maxstuff:12g_fl", "maxstuff:12g_db",
            "ea:145x114", "suffuse:120mm", "other:50bmg", "tacz:50bmg_extra",
            "tacz:unknown", "sbw_flans:762x54r_tracer")) {
            assertEquals(0.2f, TaczAircraftDamage.damageForAmmo(ResourceLocation(id), 130f), id)
        }
        assertEquals(0.2f, TaczAircraftDamage.damageForAmmo(null, 130f))
        assertEquals(39f, 130f - TaczAircraftDamage.damageForAmmo(ResourceLocation("tacz:rpg_rocket"), 130f), 0.0001f)
        assertEquals(280f, TaczAircraftDamage.damageForAmmo(ResourceLocation("tacz:rpg_rocket"), 400f), 0.001f)
        assertEquals(350f, TaczAircraftDamage.damageForAmmo(ResourceLocation("tacz:rpg_rocket"), 2000f), 0.001f)
    }

    @Test
    fun `TacZ override precedes any profile but SBW caliber bands remain exact`() {
        for (caliber in listOf(null, Double.NaN, 7.62, 12.7, 30.0, 125.0)) {
            assertEquals(0.2f, AircraftProjectileHitPolicy.resolveDamage(caliber, 0.2f))
            assertEquals(3f, AircraftProjectileHitPolicy.resolveDamage(caliber, 3f))
            assertEquals(87.1f, AircraftProjectileHitPolicy.resolveDamage(caliber, 87.1f))
        }
        assertEquals(2.5f, AircraftProjectileHitPolicy.resolveDamage(12.7, null))
        assertEquals(26f, AircraftProjectileHitPolicy.resolveDamage(30.0, null))
        assertNull(AircraftProjectileHitPolicy.resolveDamage(null, null))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f)) {
            assertNull(AircraftProjectileHitPolicy.resolveDamage(125.0, invalid))
        }
    }

    @Test
    fun `individual pellets and profiled bullets share once only direct and blast receipts`() {
        val target = UUID(80, 1)
        for ((id, count, expected) in listOf(Triple("tacz:12g", 8, 1.6f),
            Triple("tacz:50bmg", 2, 5f), Triple("sbw_flans:pg7v", 1, 91f))) {
            var applied = 0f
            repeat(count) {
                val projectile = CompoundTag()
                assertTrue(AircraftProjectileHitReceipts.claim(projectile, target))
                applied += AircraftProjectileHitPolicy.resolveDamage(125.0,
                    TaczAircraftDamage.damageForAmmo(ResourceLocation(id), 130f))!!
                for (explosion in listOf(false, true, true)) {
                    assertEquals(AircraftProjectileDamageRoute.DUPLICATE,
                        AircraftProjectileHitPolicy.nativeRoute(
                            AircraftProjectileHitReceipts.contains(projectile, target), explosion, true))
                }
            }
            assertEquals(expected, applied, 0.00001f, id)
        }
    }

    @Test
    fun `read only installed ammo census covers fifty caliber definitions and default policy`() {
        val root = System.getenv("SBW_TACZ_GUNPACK_ROOT")?.let(Path::of)
        assumeTrue(root != null && Files.isDirectory(root), "Optional installed gunpack census root not supplied")
        val pattern = Regex("/data/([^/]+)/index/ammo/(.+)\\.json$")
        val ids = Files.walk(root!!).use { files ->
            files.filter { Files.isRegularFile(it) }.map { file ->
                pattern.find(file.toString().replace('\\', '/'))?.let { match ->
                    "${match.groupValues[1]}:${match.groupValues[2]}"
                }
            }.filter { it != null }.toList().filterNotNull().toSet()
        }
        assertEquals(135, ids.size, "Installed source census changed; review exact ammo identities")
        assertTrue(ids.containsAll(fiftyCalibreIds))
        for (id in ids) {
            assertEquals(if (id in setOf("tacz:rpg_rocket", "sbw_flans:pg7v")) 91f
                else if (id in fiftyCalibreIds) 2.5f else 0.2f,
                TaczAircraftDamage.damageForAmmo(ResourceLocation(id), 130f), 0.0001f, id)
        }
    }
}
