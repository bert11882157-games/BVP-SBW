package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.network.AircraftArmamentNetwork
import com.google.gson.JsonParser
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class AircraftArmamentValidationTest {
    @Test fun `Kotlin for Forge can discover instance event handlers`() {
        for (subscriber in listOf(AircraftArmamentRegistry::class.java, AircraftArmamentManager::class.java,
            com.atsuishio.superbwarfare.diagnostics.AircraftArmamentDiagnostics::class.java)) {
            val handlers = subscriber.declaredMethods.filter {
                it.isAnnotationPresent(net.minecraftforge.eventbus.api.SubscribeEvent::class.java)
            }
            assertTrue(handlers.isNotEmpty())
            assertTrue(handlers.none { java.lang.reflect.Modifier.isStatic(it.modifiers) },
                "Kotlin for Forge registers the object instance, which excludes static subscribers")
        }
    }

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun aircraft() = json("""{"Schema":1,"Name":"Fixture","BuiltInWeapons":["Cannon"],
        "SuspendedWeapons":["Rockets"],"Pairs":[{"Id":"outer","Name":"Outer pair",
        "Left":[-2,0,0],"Right":[2,0,0],"AllowedStores":["fixture:rocket"]}]}""")

    @Test fun `neutral aircraft and visual AAM definitions are admitted without inventing weapons`() {
        AircraftArmamentRegistry.validate(aircraft(), false)
        AircraftArmamentRegistry.validate(json("""{"Schema":1,"Name":"AAM","Category":"AIR_TO_AIR","Item":"minecraft:stick"}"""), true)
        val empty = aircraft(); empty.getAsJsonArray("Pairs").remove(0)
        AircraftArmamentRegistry.validate(empty, false)
    }

    private fun withCenterline() = aircraft().also { definition ->
        definition.add("Singles", JsonParser.parseString("""[{"Id":"center","Name":"Centerline",
            "Position":[0,-0.5,1],"AllowedStores":["fixture:rocket"],"WeaponId":"Rockets",
            "StoreGroups":{"fixture:rocket":["suspended_center"]}}]"""))
    }

    @Test fun `store launch offset is a bounded finite vehicle-local vector`() {
        val store = json("""{"Schema":1,"Name":"Missile","Category":"LASER_GUIDED","LaunchOffset":[0,-0.5,0]}""")
        AircraftArmamentRegistry.validate(store, true)
        for (offset in listOf("[0,9,0]", "[0,0]", "[0,1e309,0]")) {
            val invalid = store.deepCopy().apply { add("LaunchOffset", JsonParser.parseString(offset)) }
            assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(invalid, true) }
        }
    }

    @Test fun `single station has one capacity and origin while pairs alternate both sides`() {
        val definition = withCenterline()
        AircraftArmamentRegistry.validate(definition, false)
        val mounts = AircraftArmamentRegistry.mounts(definition)
        assertEquals(2, mounts.size)
        assertEquals(6, AircraftArmamentRegistry.mountCapacity(mounts[0], 3))
        assertEquals(3, AircraftArmamentRegistry.mountCapacity(mounts[1], 3))
        for (shot in 0..5) {
            assertEquals(Vec3(if (shot % 2 == 0) -2.0 else 2.0, 0.0, 0.0),
                AircraftArmamentRegistry.launchPosition(mounts[0], shot))
            assertEquals(Vec3(0.0, -0.5, 1.0), AircraftArmamentRegistry.launchPosition(mounts[1], shot))
        }
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.launchPosition(mounts[1], -1) }
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.mountCapacity(mounts[1], 0) }
    }

    @Test fun `mixed or incomplete single geometry and shared equipment keys are rejected`() {
        for (mutation in listOf<(com.google.gson.JsonObject) -> Unit>(
            { it.getAsJsonArray("Singles")[0].asJsonObject.addProperty("Id", "outer") },
            { it.getAsJsonArray("Singles")[0].asJsonObject.remove("Position") },
            { it.getAsJsonArray("Singles")[0].asJsonObject.add("Left", JsonParser.parseString("[0,0,0]")) },
            { it.getAsJsonArray("Pairs")[0].asJsonObject.add("Position", JsonParser.parseString("[0,0,0]")) },
            { it.getAsJsonArray("Singles")[0].asJsonObject.add("Position", JsonParser.parseString("[0,0,129]")) },
        )) {
            val definition = withCenterline().also(mutation)
            assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(definition, false) }
        }
    }

    @Test fun `client snapshot preserves single selection geometry and authored visibility`() {
        val raw = json("""{"Vehicle":"00000000-0000-0000-0000-000000000001","EntityId":7,"Revision":1,
            "Stores":{"fixture:rocket":{"Schema":1,"Name":"Fixture","Category":"ROCKET_POD","Capacity":3}},
            "Selections":{"center":"fixture:rocket"},"Presets":{"saved":{"center":"fixture:rocket"}}}""")
        raw.add("Definition", withCenterline())
        val state = com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot.decode(raw)
        assertNotNull(state)
        assertEquals(1, state!!.definition.pairs.size)
        assertEquals(1, state.definition.singles.size)
        assertEquals(listOf(2, 1), state.definition.mounts.map { it.positions.size })
        assertEquals(setOf("suspended_center"), state.visibleBones())
        assertEquals(state.selections, state.presets["saved"])
        raw.getAsJsonObject("Definition").getAsJsonArray("Singles")[0].asJsonObject.addProperty("Id", "outer")
        assertNull(com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot.decode(raw))
        assertEquals("center", state.definition.singles[0].id)
    }
    @Test fun `duplicate pairs and nonfinite mount positions fail closed`() {
        val duplicate = aircraft(); duplicate.getAsJsonArray("Pairs").add(duplicate.getAsJsonArray("Pairs")[0].deepCopy())
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(duplicate, false) }
        val invalid = aircraft(); invalid.getAsJsonArray("Pairs")[0].asJsonObject.getAsJsonArray("Left").set(0,
            com.google.gson.JsonPrimitive(Double.NaN))
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(invalid, false) }
        val unnamed = aircraft(); unnamed.getAsJsonArray("Pairs")[0].asJsonObject.remove("Name")
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(unnamed, false) }
    }
    @Test fun `pod requires a real authored origin and source with valid gimbal limits`() {
        val invalid = aircraft(); invalid.add("Pod", json("""{"Position":[0,0,0],"YawLimit":170,"PitchMin":20,"PitchMax":-90,"Source":"fixture"}"""))
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(invalid, false) }
        invalid.getAsJsonObject("Pod").addProperty("PitchMin", -90)
        invalid.getAsJsonObject("Pod").addProperty("PitchMax", 20)
        AircraftArmamentRegistry.validate(invalid, false)
        invalid.getAsJsonObject("Pod").remove("Source")
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(invalid, false) }
    }
    @Test fun `request parsing rejects oversized and deeply nested input but accepts quoted brackets`() {
        assertNull(AircraftArmamentNetwork.parseRequest("{" + " ".repeat(4096) + "}"))
        assertNull(AircraftArmamentNetwork.parseRequest("{\"a\":".repeat(9) + "0" + "}".repeat(9)))
        assertNull(AircraftArmamentNetwork.parseRequest("[]"))
        assertNotNull(AircraftArmamentNetwork.parseRequest("""{"Name":"[[[[[[[[[[[["}"""))
    }
    @Test fun `designation repaint and clear survive save load and remain aircraft scoped`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val data = AircraftDesignationData()
        assertTrue(data.put(a, Vec3(1.0, 2.0, 3.0)))
        assertTrue(data.put(b, Vec3(9.0, 8.0, 7.0)))
        assertTrue(data.put(a, Vec3(4.0, 5.0, 6.0)))
        val saved = AircraftDesignationData.load(data.save(CompoundTag()))
        assertEquals(Vec3(4.0, 5.0, 6.0), saved.get(a)?.position)
        assertEquals(2L, saved.get(a)?.revision)
        assertEquals(Vec3(9.0, 8.0, 7.0), saved.get(b)?.position)
        saved.put(a, null)
        val cleared = AircraftDesignationData.load(saved.save(CompoundTag()))
        assertNull(cleared.get(a)?.position); assertEquals(3L, cleared.get(a)?.revision)
        assertNotNull(cleared.get(b)?.position)
    }
    @Test fun `designation capacity does not evict active destinations`() {
        val data = AircraftDesignationData(); val first = UUID.randomUUID()
        data.put(first, Vec3(1.0, 2.0, 3.0))
        repeat(4095) { assertTrue(data.put(UUID.randomUUID(), Vec3.ZERO)) }
        assertFalse(data.put(UUID.randomUUID(), Vec3.ZERO))
        assertTrue(data.put(first, Vec3(2.0, 3.0, 4.0)))
        assertEquals(Vec3(2.0, 3.0, 4.0), data.get(first)?.position)
    }
}
