package com.atsuishio.superbwarfare.data.container

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.profiling.ProfilerFiller
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The disabled-vehicle list (bvp data/berts_vehicle_pack/disabled_vehicles.json, bert 2026-10-23) hides vehicles from
 * the creative tabs and the lucky-container loot while their entity types, renderers and assets stay registered.
 * Skipped when the BVP sources are not beside this project.
 */
class DisabledVehiclesTest {
    private val bvp = File(System.getProperty("bvp.root") ?: "../bvp")
    private val main = File(bvp, "src/main")
    private val data = File(bvp, "src/generated/resources/data/berts_vehicle_pack")
    private val assets = File(bvp, "src/generated/resources/assets/berts_vehicle_pack")
    private val java = File(main, "java/com/yourname/berts_vehicle_pack/init")

    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }
    private fun disabled(): List<String> =
        json(File(main, "resources/data/berts_vehicle_pack/disabled_vehicles.json"))
            .getAsJsonArray("disabled").map { it.asString }

    @AfterEach fun reset() {
        ContainerDataManager.hiddenTypes.clear()
    }

    private fun apply(objects: Map<ResourceLocation, JsonElement>) {
        val manager = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResourceManager::class.java)) { _, _, _ -> null }
        val profiler = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProfilerFiller::class.java)) { _, _, _ -> null }
        // apply is protected in the reload listener; it is the real entry the game calls after the JSON is read
        val apply = ContainerDataManager::class.java.getDeclaredMethod(
            "apply", MutableMap::class.java, ResourceManager::class.java, ProfilerFiller::class.java
        )
        apply.isAccessible = true
        apply.invoke(ContainerDataManager, objects.toMutableMap(), manager, profiler)
    }

    @Test fun `hidden types are dropped from container lists in both entry forms and others stay`() {
        val loc = ResourceLocation("test", "list")
        val list = JsonParser.parseString(
            """{"List":["x:a",{"Type":"x:b","Weight":5},{"Type":"x:c","Weight":2},"x:d"]}"""
        )
        ContainerDataManager.hiddenTypes.addAll(setOf("x:b", "x:d"))
        apply(mapOf(loc to list))
        assertEquals(listOf("x:a", "x:c"), ContainerDataManager.getEntityTypes(loc).map { it.first() })
        ContainerDataManager.hiddenTypes.clear()
        apply(mapOf(loc to list))
        assertEquals(listOf("x:a", "x:b", "x:c", "x:d"), ContainerDataManager.getEntityTypes(loc).map { it.first() })
    }

    @Test fun `disabled ids leave the real BVP loot lists but stay registered with their assets`() {
        assumeTrue(main.isDirectory && data.isDirectory, "BVP sources not present")
        val ids = disabled()
        val hidden = ids.map { "berts_vehicle_pack:$it" }.toSet()
        ContainerDataManager.hiddenTypes.addAll(hidden)
        val lists = listOf("mobile_vehicles", "land_vehicles").associate {
            ResourceLocation("berts_vehicle_pack", it) to JsonParser.parseReader(File(data, "sbw/containers/$it.json").reader())
        }
        apply(lists)
        val entities = File(java, "ModEntities.java").readText()
        val renderers = File(java, "ModEntityRenderers.java").readText()
        for (loc in lists.keys) {
            val left = ContainerDataManager.getEntityTypes(loc).map { it.first() }
            assertTrue(left.isNotEmpty(), "$loc emptied")
            for (h in hidden) assertFalse(h in left, "$loc still lists $h")
        }
        // Visible neighbours of the same series are untouched (the F-15C is the expansion's new-model vehicle).
        assertTrue("berts_vehicle_pack:f_15c" in ContainerDataManager.getEntityTypes(ResourceLocation("berts_vehicle_pack", "mobile_vehicles")).map { it.first() })
        for (id in ids) {
            assertTrue(entities.contains("ENTITIES.register(\"$id\""), "$id must stay registered in ModEntities")
            assertTrue(renderers.contains("\"custom_geo/$id.geo.json\""), "$id must keep its renderer")
            for (rel in listOf("custom_geo/$id.geo.json", "textures/entity/$id.png", "sbw/vehicles/$id.json")) {
                assertTrue(File(assets, rel).isFile, "$id lost asset $rel")
            }
        }
    }

    @Test fun `creative tabs are filtered at load and the generated tab file is left alone`() {
        assumeTrue(main.isDirectory && data.isDirectory, "BVP sources not present")
        val catalog = File(java, "BvpCreativeCatalog.java").readText()
        assertTrue(catalog.contains("BvpDisabledVehicles.visible("), "creative catalog must drop disabled ids")
        assertTrue(catalog.contains("BvpDisabledVehicles.isDisabled(icon)"), "a disabled tab icon must fail loudly")
        val tabs = json(File(data, "creative_tabs.json")).getAsJsonArray("categories")
        val aircraft = tabs.map { it.asJsonObject }.first { it.get("id").asString == "aircraft" }
        assertFalse(disabled().contains(aircraft.get("iconVehicleId").asString), "tab icon is disabled")
        // The list is the only switch: ids stay in the generated tab file, so emptying the array restores them.
        val inTab = aircraft.getAsJsonArray("vehicleIds").map { it.asString }
        for (id in disabled()) assertTrue(id in inTab, "$id should still be in the generated tab file")
    }

    @Test fun `the disabled list holds only old-model F-14 and F-16 variants and keeps the swapped ones visible`() {
        assumeTrue(main.isDirectory, "BVP sources not present")
        val ids = disabled() // empty is allowed: that is the restored state
        assertTrue(setOf("f_14a", "f_14d", "f_16b").containsAll(ids), "unexpected disabled ids $ids")
        for (keep in listOf("f_15c", "f_15e", "f_16c", "f_4c", "f_14c", "f_16a", "f_15a", "f_15ex", "f_4b", "f_4d", "f_4e", "fa_18e", "a_10")) {
            assertFalse(keep in ids, "$keep is an expansion model and must stay visible")
        }
    }
}
