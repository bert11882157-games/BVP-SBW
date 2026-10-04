package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The pack's generated aircraft stores and armaments pass the loader's validation, and every station only offers
 * stores that exist. Runs against the Bert's Vehicle Pack data beside this project (skipped when it is not there,
 * e.g. in a tree that holds only sbw/); -Dbvp.sbwData=<dir> points it elsewhere.
 */
class AircraftStoreCatalogTest {
    private val root = File(System.getProperty("bvp.sbwData")
        ?: "../bvp/src/generated/resources/data/berts_vehicle_pack/sbw")

    private fun json(file: File) = JsonParser.parseString(file.readText()).asJsonObject

    @Test fun `every generated store validates and every allowed store exists`() {
        assumeTrue(File(root, "aircraft_stores").isDirectory, "pack data not beside this project")
        val stores = File(root, "aircraft_stores").walkTopDown().filter { it.isFile && it.extension == "json" &&
            !it.path.contains("modeled_store") }.associateBy { f ->
            "berts_vehicle_pack:" + f.relativeTo(File(root, "aircraft_stores")).path.removeSuffix(".json").replace('\\', '/')
        }
        val failures = mutableListOf<String>()
        for ((id, file) in stores) {
            runCatching { AircraftArmamentRegistry.validate(json(file), true) }
                .onFailure { failures += "$id: ${it.message ?: it.javaClass.simpleName}" }
        }
        val modeled = File(root, "aircraft_stores/modeled_store").listFiles()?.map {
            "berts_vehicle_pack:modeled_store/" + it.nameWithoutExtension }.orEmpty().toSet()
        for (file in File(root, "aircraft_armaments").listFiles().orEmpty().filter { it.extension == "json" }) {
            val arm = json(file)
            runCatching { AircraftArmamentRegistry.validate(arm, false) }
                .onFailure { failures += "${file.name}: ${it.message ?: it.javaClass.simpleName}" }
            for (key in listOf("Pairs", "Singles")) arm.getAsJsonArray(key)?.forEach { pair ->
                pair.asJsonObject.getAsJsonArray("AllowedStores")?.forEach { store ->
                    val id = store.asString
                    if (id !in stores && id !in modeled) failures += "${file.name}: unknown store $id"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test fun `every offered rocket or gun pod has a native weapon to fire`() {
        assumeTrue(File(root, "aircraft_stores").isDirectory, "pack data not beside this project")
        val pods = File(root, "aircraft_stores").walkTopDown().filter { it.isFile && it.extension == "json" }
            .filter { json(it)["Category"]?.asString in setOf("ROCKET_POD", "GUN_POD") }
            .map { "berts_vehicle_pack:" + it.relativeTo(File(root, "aircraft_stores")).path.removeSuffix(".json").replace('\\', '/') }
            .toSet()
        val silent = mutableListOf<String>()
        for (file in File(root, "aircraft_armaments").listFiles().orEmpty().filter { it.extension == "json" }) {
            val arm = json(file)
            for (key in listOf("Pairs", "Singles")) arm.getAsJsonArray(key)?.forEach { element ->
                val pair = element.asJsonObject
                pair.getAsJsonArray("AllowedStores")?.map { it.asString }?.filter { it in pods }?.forEach { id ->
                    // AircraftArmamentManager.nativeWeapons: the station's mapping for this store, else its WeaponId
                    val mapped = pair.getAsJsonObject("NativeWeaponIds")?.get(id)
                    val fires = mapped != null && (!mapped.isJsonArray || mapped.asJsonArray.size() > 0) ||
                        mapped == null && pair.has("WeaponId")
                    if (!fires) silent += "${file.nameWithoutExtension}/${pair["Id"].asString}: $id"
                }
            }
        }
        assertTrue(silent.isEmpty(), "pods offered where nothing fires them:\n" + silent.joinToString("\n"))
    }

    @Test fun `television weapons are command guided TV stores`() {
        assumeTrue(File(root, "aircraft_stores").isDirectory, "pack data not beside this project")
        // (the Kh-59M arrived 2026-10-02; an older pack beside this project lacks it)
        for (path in listOf("fa18e/agm65", "kh25mt", "munition/kd88", "munition/kh59m")
            .filter { it != "munition/kh59m" || File(root, "aircraft_stores/$it.json").isFile }) {
            val store = json(File(root, "aircraft_stores/$path.json"))
            assertEquals("COMMAND_GUIDED", store["Category"].asString, path)
            assertEquals("TV", store.getAsJsonObject("CommandGuidance")["Mode"].asString, path)
            val gun = store["LaunchGunProfile"].asString.substringAfter(':')
            assertTrue(File(root, "guns/$gun.json").isFile, "$path launch gun")
            assertTrue(File(root, "projectile_profiles/${store["ProjectileProfile"].asString.substringAfter(':')}.json").isFile)
        }
    }

    /**
     * Owner 2026-10-02: TV missiles turn no faster than the missile they are based on (AGM-65B <- AGM-65D, Kh-25MT <-
     * Kh-25ML, KD-88 TV <- KD-88 IIR, Kh-59M <- Kh-59MK2), with no body-turn allowance above that rate.
     */
    @Test fun `television missiles turn no faster than their base missile`() {
        assumeTrue(File(root, "aircraft_stores/munition/kh59mk2.json").isFile, "pack data (2026-10-02+) not beside this project")
        fun profile(path: String) = json(File(root, "projectile_profiles/" +
            json(File(root, "aircraft_stores/$path.json"))["ProjectileProfile"].asString.substringAfter(':') + ".json"))
        fun storeRate(path: String): Double {
            val flight = json(File(root, "aircraft_stores/$path.json")).getAsJsonObject("Flight")
            val rate = flight["TurnDegreesPerSecond"].asDouble
            val g = flight["MaxLoadFactorG"]?.asDouble ?: return rate
            return minOf(rate, Math.toDegrees(g * 9.80665 / (flight["MaxSpeed"].asDouble * 20.0)))
        }
        val base = mapOf("fa18e/agm65" to storeRate("fa18e/agm65d"), "munition/kd88" to storeRate("munition/kd88_ir"),
            "munition/kh59m" to storeRate("munition/kh59mk2"), "kh25mt" to 42.2)
        for ((path, limit) in base) {
            val p = profile(path)
            val rate = p.getAsJsonObject("GuidedPropulsion")["MaxTurnRateDegreesPerSecond"].asDouble
            assertTrue(rate <= limit + 0.1, "$path turns $rate deg/s, base $limit")
            val scale = p.getAsJsonObject("Extensions")?.getAsJsonObject("superbwarfare:guided_maneuver_v1")
                ?.get("BodyTurnLimitScale")?.asDouble ?: 1.0
            assertEquals(1.0, scale, "$path body turn allowance")
        }
    }

    /** Owner 2026-10-02: "KH-59 should only have a GPS guided and TV guided version". */
    @Test fun `the Kh-59 comes as GPS cruise and TV only`() {
        assumeTrue(File(root, "aircraft_stores/munition/kh59mk2.json").isFile, "pack data (2026-10-02+) not beside this project")
        val kh59 = File(root, "aircraft_stores").walkTopDown().filter { it.isFile && it.extension == "json" }
            .map { it to json(it) }.filter { (_, s) -> s["Name"]?.asString?.startsWith("Kh-59") == true }.toList()
        assertEquals(setOf("kh59m", "kh59mk2"), kh59.map { it.first.nameWithoutExtension }.toSet())
        for ((file, store) in kh59) {
            when (file.nameWithoutExtension) {
                "kh59m" -> assertEquals("TV", store.getAsJsonObject("CommandGuidance")["Mode"].asString)
                else -> assertTrue(AircraftBombTargeting.isGpsStore(store), "${file.name} is a GPS store")
            }
        }
        val offered = File(root, "aircraft_armaments").listFiles()!!.filter { it.readText().contains("munition/kh59mk\"") }
        assertTrue(offered.isEmpty(), "anti-ship Kh-59MK still offered on ${offered.map { it.name }}")
    }

    /**
     * Owner 2026-10-02 ("tv guided bombs should at least try to glide"): every TV bomb has the glide figures of a real
     * glide weapon, 4:1 to 6:1, with its best glide speed at or below the 400 km/h TV cap so a release at the
     * aircraft's usual speed can hold its path (AircraftBombFlight.tvGlideDirection).
     */
    @Test fun `television bombs glide 4 to 6 to 1 at a reachable speed`() {
        assumeTrue(File(root, "aircraft_stores/munition/walleye.json").isFile, "pack data (2026-10-02+) not beside this project")
        val tv = File(root, "aircraft_stores").walkTopDown().filter { it.isFile && it.extension == "json" }
            .map { it to json(it) }
            .filter { (_, store) -> store.getAsJsonObject("Bomb")?.get("Mode")?.asString == "TV" }.toList()
        assertTrue(tv.map { it.first.nameWithoutExtension }.containsAll(
            listOf("gbu15_v2b", "kab500od", "walleye", "walleye2", "gbu8")), tv.map { it.first.name }.toString())
        for ((file, store) in tv) {
            val bomb = store.getAsJsonObject("Bomb")
            val ratio = bomb[AircraftBombFlight.Glide.LD_JSON]?.asDouble ?: AircraftBombFlight.Glide.TV_DEFAULT.liftToDrag
            val best = bomb[AircraftBombFlight.Glide.SPEED_JSON]?.asDouble ?: AircraftBombFlight.Glide.TV_DEFAULT.bestSpeed
            assertTrue(ratio in 4.0..6.0, "${file.name}: L/D $ratio")
            assertTrue(best <= AircraftBombFlight.TV_SPEED_CAP, "${file.name}: best glide speed $best")
        }
    }
}
