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
        for (path in listOf("fa18e/agm65", "kh25mt", "munition/kd88")) {
            val store = json(File(root, "aircraft_stores/$path.json"))
            assertEquals("COMMAND_GUIDED", store["Category"].asString, path)
            assertEquals("TV", store.getAsJsonObject("CommandGuidance")["Mode"].asString, path)
            val gun = store["LaunchGunProfile"].asString.substringAfter(':')
            assertTrue(File(root, "guns/$gun.json").isFile, "$path launch gun")
            assertTrue(File(root, "projectile_profiles/${store["ProjectileProfile"].asString.substringAfter(':')}.json").isFile)
        }
    }
}
