package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Every belt round in the BVP data names an ammo identity that GunData.copyForProjectileBeltAmmo can find: one of
 * its weapon's AmmoType or ProjectileBeltAmmoType consumers, unique across both. A missing one is
 * MISSING_COMPONENT_TARGET at that belt phase, and since a refused shot never advances the phase the gun stays silent
 * from there on (test8: the f_22a Vulcan's Air Belt fired 3 HEF-I and stopped at its API-T, 2026-10-24).
 * Skipped when the BVP sources are not beside this project.
 */
class BvpProjectileBeltReferenceTest {
    private val vehicles = File(System.getProperty("bvp.root") ?: "../bvp",
        "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles")

    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }
    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()
    private fun JsonObject.ammo(): String? = get("Ammo")?.takeIf { it.isJsonPrimitive }?.asString

    /** "<vehicle> <weapon>: ..." for every unresolvable round or duplicate consumer identity */
    private fun problems(file: File): List<String> {
        val weapons = json(file).getAsJsonObject("Weapons") ?: return emptyList()
        val id = file.nameWithoutExtension
        return weapons.entrySet().flatMap { (name, element) ->
            if (!element.isJsonObject) return@flatMap emptyList()
            val weapon = element.asJsonObject
            val consumers = (weapon.array("AmmoType") + weapon.array("ProjectileBeltAmmoType"))
                .filter { it.isJsonObject }.map { it.asJsonObject }
            val identities = consumers.mapNotNull { it.ammo() }
            val out = mutableListOf<String>()
            val belts = buildList {
                weapon.getAsJsonObject("ProjectileBelt")?.let { add(it) }
                weapon.array("AmmoType").filter { it.isJsonObject }
                    .forEach { e -> e.asJsonObject.getAsJsonObject("Override")?.getAsJsonObject("ProjectileBelt")?.let { add(it) } }
            }
            // the resolver only checks uniqueness while resolving a belt round, so beltless weapons may repeat ids
            if (belts.any { it.array("Rounds").any { r -> r.asJsonObject.ammo() != null } }) {
                identities.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
                    .forEach { out += "$id $name: duplicate consumer $it" }
            }
            for (belt in belts) for (round in belt.array("Rounds")) {
                val ammo = round.asJsonObject.ammo() ?: continue
                if (ammo !in identities) {
                    out += "$id $name belt ${belt.get("Name")}: round ${round.asJsonObject.get("Round")} -> $ammo has no consumer"
                }
            }
            out
        }
    }

    @Test fun `the f_22a Vulcan belts resolve every round`() {
        assumeTrue(vehicles.isDirectory)
        assertEquals(emptyList<String>(), problems(File(vehicles, "f_22a.json")))
    }

    @Test fun `every belt round in the pack resolves to a consumer`() {
        assumeTrue(vehicles.isDirectory)
        val all = vehicles.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.flatMap(::problems)
        assertEquals(emptyList<String>(), all)
    }
}
