package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudAmmo
import com.atsuishio.superbwarfare.client.weapon.VehicleWeaponHudMetadata
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** Authored-model acceptance, not a live entity/ammunition simulation. No alternate firing model. */
class HelicopterGroundWeaponModelTest {
    private val pack = File(System.getProperty("bvp.projectDir") ?: "../bvp")
    private val ids = setOf("ah1w_super_cobra", "ah_1f", "ah_1g_cobra", "ah_56a", "ah_64d",
        "ah_6j", "ch_46e", "eurocopter_tiger", "ka50", "mi24v", "mi28n", "mi_24a", "mi_24d",
        "mi_24p", "mi_26", "mi_8amtsh", "sa341f_gazelle")
    private fun resource(path: String): JsonObject {
        val relative = "resources/data/berts_vehicle_pack/sbw/$path.json"
        val overlay = File(pack, "src/main/$relative")
        val file = overlay.takeIf { it.isFile } ?: File(pack, "src/generated/$relative")
        assertTrue(file.isFile, "Required effective helicopter resource missing: $file")
        return JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
    }
    private fun vehicle(id: String) = resource("vehicles/$id")
    private fun arm(id: String) = resource("aircraft_armaments/$id")
    private fun seats(id: String) = vehicle(id).getAsJsonArray("Seats").map {
        it.asJsonObject.getAsJsonArray("Weapons")?.map { name -> name.asString }.orEmpty()
    }
    private fun channels(mount: JsonObject, store: String): List<String> {
        val value = mount.getAsJsonObject("NativeWeaponIds")?.get(store) ?: mount["WeaponId"] ?: return emptyList()
        return if (value.isJsonArray) value.asJsonArray.map { it.asString } else listOf(value.asString)
    }
    private fun offered(id: String, store: String): List<AircraftWeaponGroups.Mount> =
        AircraftArmamentRegistry.mounts(arm(id)).filter { mount ->
            mount.getAsJsonArray("AllowedStores").any { it.asString == store }
        }.map { mount -> AircraftWeaponGroups.Mount(mount["Id"].asString, store,
            resource("aircraft_stores/${store.substringAfter(':')}")["Category"].asString, channels(mount, store)) }

    @Test fun `all seventeen registered helicopter definitions inherit shared BVP helicopter policy`() {
        val sources = File(pack, "src/main/java/com/yourname/berts_vehicle_pack")
        assertTrue(sources.isDirectory, "BVP source required; missing census cannot pass")
        fun stripComments(s: String) = s.replace(Regex("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*"), "")
        val parents = sources.walkTopDown().filter { it.isFile && it.extension == "java" }.flatMap {
            Regex("class\\s+(\\w+)\\s+extends\\s+(\\w+)").findAll(stripComments(it.readText()))
                .map { match -> match.groupValues[1] to match.groupValues[2] }
        }.toMap()
        fun helicopter(type: String): Boolean {
            var current = type
            val seen = mutableSetOf<String>()
            while (seen.add(current)) {
                if (current == "BvpHelicopterEntity") return true
                current = parents[current] ?: return false
            }
            return false
        }
        val registration = stripComments(File(sources, "init/ModEntities.java").readText())
        val registered = Regex("RegistryObject<EntityType<(\\w+)>>\\s+\\w+\\s*=\\s*ENTITIES\\.register\\(\"([^\"]+)\"")
            .findAll(registration).filter { helicopter(it.groupValues[1]) }.map { it.groupValues[2] }.toSet()
        assertEquals(ids, registered, "Registration/inheritance census changed; review actual helicopter scope")
        for (id in registered) {
            assertEquals("berts_vehicle_pack:$id", vehicle(id)["ID"].asString)
            assertTrue(seats(id).isNotEmpty(), "$id crew definition")
        }
    }

    @Test fun `every offered native feed is defined and reachable by a real seat`() {
        var nativeMappings = 0
        var releasedChoices = 0
        for (id in ids - setOf("ch_46e", "mi_26")) {
            val definition = arm(id)
            val crew = seats(id)
            val weapons = vehicle(id).getAsJsonObject("Weapons")
            val operators = AircraftMissileLaunchers.weaponSeats(definition)
            assertTrue(operators.isNotEmpty() && operators.all { it in crew.indices }, "$id operator seats")
            val mounts = AircraftArmamentRegistry.mounts(definition)
            assertTrue(mounts.isNotEmpty(), "$id mounts")
            for (mount in mounts) for (choice in mount.getAsJsonArray("AllowedStores")) {
                val store = choice.asString
                val category = resource("aircraft_stores/${store.substringAfter(':')}")["Category"].asString
                if (category in setOf("ROCKET_POD", "GUN_POD", "MISSILE_LAUNCHER")) {
                    val members = channels(mount, store)
                    assertTrue(members.isNotEmpty(), "$id/${mount["Id"]}/$store has no firing feed")
                    for (member in members) {
                        assertTrue(weapons.has(member), "$id/$store missing weapon $member")
                        assertTrue(crew.any { member in it }, "$id/$store orphan feed $member")
                        nativeMappings++
                    }
                } else if (category !in setOf("VISUAL_ONLY", AircraftTargetingPods.CATEGORY)) {
                    assertTrue(operators.any { AircraftMissileLaunchers.isWeaponSeat(definition, it) }, "$id/$store release operator")
                    releasedChoices++
                }
            }
        }
        assertTrue(nativeMappings > 100, "Non-vacuous native feed coverage: $nativeMappings")
        assertTrue(releasedChoices > 50, "Non-vacuous released-store coverage: $releasedChoices")
        for (id in listOf("ch_46e", "mi_26")) assertTrue(seats(id).all { it.isEmpty() }, "$id stays unarmed")
    }

    @Test fun `shared Apache gun does not erase Hind and Hip seat ownership`() {
        val apache = seats("ah_64d")
        assertEquals(apache[0].toSet(), apache[1].toSet())
        assertEquals("M230", apache[1].first(), "The gunner retains its own authored slot ordering")
        assertTrue("M230" in apache[0])
        assertNotEquals(apache[0], apache[1], "Shared systems do not imply a shared seat selector order")
        assertEquals(listOf(1, 0), AircraftMissileLaunchers.weaponSeats(arm("ah_64d")))
        val hind = seats("mi_24p")
        assertTrue("Gsh30k" in hind[0])
        assertEquals(listOf("ShturmTip", "ShturmOuter"), hind[1])
        assertTrue(hind[0].intersect(hind[1].toSet()).isEmpty())
        val hip = seats("mi_8amtsh")
        assertEquals(listOf("ShturmOuter", "AtakaOuter"), hip[1])
        assertEquals(listOf("PassengerGun1"), hip[3])
        assertFalse("PassengerGun1" in hip[0] || "PassengerGun1" in hip[1])
        for (id in listOf("mi_24p", "mi_8amtsh"))
            assertEquals(listOf(1, 0), AircraftMissileLaunchers.weaponSeats(arm(id)), "$id released stores are separately authorized")
    }

    @Test fun `actual Apache equipped Hydra groups keep four physical feeds and one selector even empty`() {
        val mounts = offered("ah_64d", "berts_vehicle_pack:heli/m261_m247")
        assertEquals(4, mounts.size)
        val group = AircraftWeaponGroups.groups(mounts).single()
        assertEquals(listOf("HydraS2", "HydraS3", "HydraS4", "HydraS5"), group.members)
        assertEquals("HydraS2", group.representative)
        // Fit identity is independent of ammunition; depleted state does not remove the equipped group.
        assertTrue(AircraftWeaponGroups.orderedLiveMounts(group.mounts, 0) { 0 }.isEmpty())
        assertEquals(group, AircraftWeaponGroups.groups(mounts).single())
        assertEquals(0, VehicleWeaponHudMetadata.groupReloadTicks(listOf(0 to 80, 1 to 0, 0 to 0, 0 to 0)))
        assertEquals(40, VehicleWeaponHudMetadata.groupReloadTicks(listOf(0 to 80, 0 to 40, 0 to 0, 0 to 0)))
        assertEquals(0, VehicleWeaponHudMetadata.groupReloadTicks(List(4) { 0 to 0 }))
        assertEquals(VehicleWeaponHudAmmo(0, 0, false), VehicleWeaponHudAmmo.from(76, 0, 0, false))
    }

    @Test fun `actual native Shturm and Ataka mounts never become disposable virtual magazine groups`() {
        val cases = listOf(
            Triple("mi_24p", "berts_vehicle_pack:mi24p/shturm_x2", "ShturmTip"),
            Triple("mi_24p", "berts_vehicle_pack:mi24p/shturm_x2_pylon", "ShturmOuter"),
            Triple("mi_8amtsh", "berts_vehicle_pack:mi_8amtsh/ataka_x4", "AtakaOuter"))
        for ((id, store, channel) in cases) {
            val mounts = offered(id, store)
            assertEquals(1, mounts.size, "$id/$store")
            assertEquals("MISSILE_LAUNCHER", mounts.single().category)
            assertEquals(listOf(channel), mounts.single().channels)
            assertTrue(AircraftWeaponGroups.groups(mounts).isEmpty(), "$channel must retain native magazine identity")
            assertTrue(channel in seats(id)[1])
        }
    }

    @Test fun `actual Apache released Hellfires aggregate mounts and keep live release order after depletion`() {
        val mounts = offered("ah_64d", "berts_vehicle_pack:heli/m279_agm114k")
        assertEquals(4, mounts.size)
        val group = AircraftWeaponGroups.groups(mounts).single()
        assertEquals(mounts.map { "AircraftStore:${it.id}" }, group.members)
        val surviving = group.mounts.last()
        assertEquals(listOf(surviving), AircraftWeaponGroups.orderedLiveMounts(group.mounts, 0) { if (it == surviving) 1 else 0 })
        assertTrue(AircraftWeaponGroups.orderedLiveMounts(group.mounts, 3) { 0 }.isEmpty())
    }
}
