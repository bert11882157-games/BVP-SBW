package com.atsuishio.superbwarfare.api.projectile

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EffectiveDamageResourcesTest {
    @TempDir lateinit var directory: File

    @Test fun `Windows resource separators produce working profile keys`() {
        assertEquals("aircraft_stores/as20.json", EffectiveDamageResources.invariant("aircraft_stores\\as20.json"))
        assertEquals("aircraft_stores/as20.json", EffectiveDamageResources.invariant("aircraft_stores/as20.json"))
    }

    @Test fun `effective lookup reads overlay bytes and keeps generated-only fallback`() {
        fun write(layer: String, path: String, hull: Int) {
            File(directory, "$layer/data/berts_vehicle_pack/sbw/projectile_profiles/$path").also {
                it.parentFile.mkdirs(); it.writeText("""{"Combat":{"HullDamage":$hull}}""")
            }
        }
        write("src/generated/resources", "aircraft_stores/shared.json", 20)
        write("src/main/resources", "aircraft_stores/shared.json", 70)
        write("src/generated/resources", "retained.json", 30)
        write("src/main/resources", "new.json", 40)
        val view = EffectiveDamageResources(directory)
        assertEquals(3, view.files.size)
        assertEquals(70, view.json("sbw/projectile_profiles/aircraft_stores/shared.json").getAsJsonObject("Combat").get("HullDamage").asInt)
        assertEquals(30, view.json("sbw/projectile_profiles/retained.json").getAsJsonObject("Combat").get("HullDamage").asInt)
        assertEquals(40, view.json("sbw/projectile_profiles/new.json").getAsJsonObject("Combat").get("HullDamage").asInt)
        assertThrows(NoSuchElementException::class.java) { view.json("sbw/projectile_profiles/missing.json") }
    }

    private fun resource(path: String, text: String) {
        File(directory, "src/generated/resources/data/berts_vehicle_pack/$path").also {
            it.parentFile.mkdirs(); it.writeText(text)
        }
    }

    @Test fun `missing typed profile reference fails with referring location`() {
        resource("sbw/vehicles/test.json", """{"Weapons":{"Cannon":{"Projectile":{"Profile":"berts_vehicle_pack:missing"}}}}""")
        val error = assertThrows(IllegalStateException::class.java) {
            EffectiveDamageResources(directory).reachableFrom(listOf("sbw/vehicles/test.json"))
        }
        assertTrue(error.message!!.contains("Weapons/Cannon/Projectile/Profile"))
        assertTrue(error.message!!.contains("missing.json"))
    }

    @Test fun `damage profile losing Combat fails while reviewed presentation profile is allowed`() {
        resource("sbw/vehicles/test.json", """{"ProjectileProfile":"berts_vehicle_pack:cannon/round"}""")
        resource("sbw/projectile_profiles/cannon/round.json", """{"VisualProfile":"berts_vehicle_pack:authored_missile"}""")
        val view = EffectiveDamageResources(directory)
        assertTrue("sbw/projectile_profiles/cannon/round.json" in view.reachableFrom(listOf("sbw/vehicles/test.json")))
        val error = assertThrows(IllegalStateException::class.java) { view.combat("cannon/round.json") }
        assertTrue(error.message!!.contains("missing Combat"))
        resource("sbw/projectile_profiles/aircraft_bombs/mk82.json", """{"VisualProfile":"berts_vehicle_pack:bomb","RenderScale":1}""")
        assertNull(EffectiveDamageResources(directory).combat("aircraft_bombs/mk82.json"))
        resource("sbw/projectile_profiles/aircraft_bombs/mk82.json", """{"VisualProfile":"berts_vehicle_pack:bomb","Combat":null}""")
        assertThrows(IllegalStateException::class.java) { EffectiveDamageResources(directory).combat("aircraft_bombs/mk82.json") }
    }
    @Test fun `no charge contract accepts explicit zero but rejects explosive or malformed values`() {
        fun parsed(text: String) = com.google.gson.JsonParser.parseString(text).asJsonObject
        assertTrue(EffectiveDamageResources.noCharge(parsed("{}")))
        assertTrue(EffectiveDamageResources.noCharge(parsed("""{"TntEquivalentKg":0.0,"ExplosionDamage":0}""")))
        for (bad in listOf("0.001", "-1", "1e999", "null", "[]", "\"0\"")) {
            assertFalse(EffectiveDamageResources.noCharge(parsed("""{"TntEquivalentKg":$bad}""")), bad)
            assertFalse(EffectiveDamageResources.noCharge(parsed("""{"ExplosionDamage":$bad}""")), bad)
        }
    }
}
