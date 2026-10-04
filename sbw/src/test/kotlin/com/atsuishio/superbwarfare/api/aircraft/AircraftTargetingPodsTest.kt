package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentSnapshot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Pylon-carried targeting pods (the Palantir test pod): compatibility, eye point, built-in precedence, loss. */
class AircraftTargetingPodsTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun near(expected: Vec3, actual: Vec3?, message: String = "") {
        assertNotNull(actual, message)
        assertTrue(expected.distanceTo(actual!!) < 1.0e-9, "$message: expected $expected, got $actual")
    }

    private val podId = "fixture:sensor/pod"
    private fun podStore() = json("""{"Schema":1,"Name":"Fixture pod","Category":"TARGETING_POD",
        "Model":"fixture:custom_geo/aircraft_stores/pod.geo.json","ModelForward":"-Z","MountAnchor":[0,0.25,-0.3],
        "Capacity":1,"MassKg":150,"AnyPylon":{"ExceptMounts":["bay_gun"]},
        "TargetingPod":{"Eye":[0,-0.0125,-1.5],"YawLimit":180,"PitchMin":-10,"PitchMax":90,"MaxZoom":24,"Range":8192}}""")
    private fun bomb() = json("""{"Schema":1,"Name":"Bomb","Category":"BOMB","Capacity":1,"MassKg":250,
        "Bomb":{"Mode":"DUMB","Gravity":0.08,"DragMultiplier":1,"TurnDegreesPerTick":0,"BlastRadius":5,"BlastDamage":100}}""")
    private fun jet() = json("""{"Schema":1,"Name":"Jet","MaxPayloadKg":5000,
        "Pairs":[{"Id":"wing","Name":"Wing pylon","Left":[3,1,0],"Right":[-3,1,0],
            "LeftStations":[{"Point":[3,0.8,-0.5],"Face":"bottom"}],"RightStations":[{"Point":[-3,0.8,-0.5],"Face":"bottom"}],
            "AllowedStores":["fixture:bomb"]},
          {"Id":"tip","Name":"Wingtip rail","Left":[5,1.2,-1],"Right":[-5,1.2,-1]}],
        "Singles":[{"Id":"centre","Name":"Centreline","Position":[0,0.6,1],"AllowedStores":["fixture:bomb"]},
          {"Id":"bay","Name":"Bomb bay","Position":[0,0.9,0],"Internal":true,"MaxPylonMassKg":2000,"AllowedStores":["fixture:bomb"]},
          {"Id":"bay_gun","Name":"Bay gun","Position":[0.5,0.9,4]}]}""")
    private fun stores() = mapOf(ResourceLocation(podId) to podStore(), ResourceLocation("fixture:bomb") to bomb())
    private fun allowed(definition: JsonObject, mount: String) = AircraftArmamentRegistry.mounts(definition)
        .first { it["Id"].asString == mount }.getAsJsonArray("AllowedStores")?.map { it.asString } ?: emptyList()

    @Test fun `pod stores validate and malformed pod blocks fail closed`() {
        AircraftArmamentRegistry.validate(podStore(), true)
        AircraftArmamentRegistry.validate(bomb(), true)
        AircraftArmamentRegistry.validate(jet(), false)
        for (broken in listOf<(JsonObject) -> Unit>(
            { it.remove("TargetingPod") },
            { it.getAsJsonObject("TargetingPod").remove("Eye") },
            { it.getAsJsonObject("TargetingPod").add("Eye", JsonParser.parseString("[0,0,40]")) },
            { it.getAsJsonObject("TargetingPod").addProperty("PitchMax", -20) },
            { it.getAsJsonObject("TargetingPod").addProperty("Zoom", 3) },
            { it.getAsJsonObject("AnyPylon").add("ExceptMounts", JsonParser.parseString("[\"bad id!\"]")) },
            { it.getAsJsonObject("AnyPylon").addProperty("Helicopters", true) },
            { it.remove("Model") },
        )) {
            val store = podStore().also(broken)
            assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(store, true) }
        }
        // only a targeting pod may carry a pod block or offer itself everywhere
        val bombWithPod = bomb().apply { add("TargetingPod", podStore()["TargetingPod"]) }
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(bombWithPod, true) }
        val bombEverywhere = bomb().apply { add("AnyPylon", JsonObject()) }
        assertThrows(IllegalArgumentException::class.java) { AircraftArmamentRegistry.validate(bombEverywhere, true) }
    }

    @Test fun `universal pod is offered on every external pylon but not bays and the registry copy is untouched`() {
        val raw = jet()
        val before = raw.toString()
        val stores = stores()
        val offered = AircraftTargetingPods.withUniversalStores(raw, stores)
        assertEquals(before, raw.toString(), "registry definition is never edited")
        assertEquals(listOf("fixture:bomb", podId), allowed(offered, "wing"))
        assertEquals(listOf(podId), allowed(offered, "tip"), "a pylon with no stores of its own still takes the pod")
        assertEquals(listOf("fixture:bomb", podId), allowed(offered, "centre"))
        assertEquals(listOf("fixture:bomb"), allowed(offered, "bay"), "internal bays are not pylons")
        assertEquals(emptyList<String>(), allowed(offered, "bay_gun"), "excepted mount")
        AircraftArmamentRegistry.validate(offered, false)
        // one object per (definition, catalogue): identity-keyed caches downstream stay valid
        assertSame(offered, AircraftTargetingPods.withUniversalStores(raw, stores))
        assertNotSame(offered, AircraftTargetingPods.withUniversalStores(raw, stores()))
        val noPods = mapOf(ResourceLocation("fixture:bomb") to bomb())
        assertSame(raw, AircraftTargetingPods.withUniversalStores(raw, noPods))
    }

    @Test fun `eye is the front of the pod as hung on its pylon station`() {
        val definition = AircraftTargetingPods.withUniversalStores(jet(), stores())
        val wing = AircraftArmamentRegistry.mounts(definition).first { it["Id"].asString == "wing" }
        // -Z model: model (dx, dy, dz) from the top anchor -> hull (-dx, dy, -dz) from the station point
        val eye = Vec3(0.0, -0.0125 - 0.25, 1.5 - 0.3)
        near(Vec3(3.0, 0.8, -0.5).add(eye), AircraftTargetingPods.eye(wing, podStore(), podId, 0), "left station")
        near(Vec3(-3.0, 0.8, -0.5).add(eye), AircraftTargetingPods.eye(wing, podStore(), podId, 1), "right station")
        val tip = AircraftArmamentRegistry.mounts(definition).first { it["Id"].asString == "tip" }
        near(Vec3(5.0, 1.2, -1.0).add(eye), AircraftTargetingPods.eye(tip, podStore(), podId, 0), "no stations: mount point")
        val pod = AircraftTargetingPods.storePod(wing, podStore(), podId, 0)
        assertEquals("wing", pod[AircraftTargetingPods.MOUNT].asString)
        assertEquals(0, pod[AircraftTargetingPods.MOUNT_POSITION].asInt)
        for (key in listOf("YawLimit", "PitchMin", "PitchMax", "MaxZoom", "Range"))
            assertEquals(podStore().getAsJsonObject("TargetingPod")[key], pod[key], key)
        // the generated pod is a valid authored-style aircraft Pod
        AircraftArmamentRegistry.validate(jet().apply { add("Pod", pod) }, false)
    }

    @Test fun `built in pod wins, a fitted pod store gives one, and losing the station removes it`() {
        val definition = AircraftTargetingPods.withUniversalStores(jet(), stores())
        val stores = stores().mapKeys { it.key.toString() }
        val none = AircraftTargetingPods.effective(definition, { null }, stores::get) { false }
        assertNull(none, "no pod without a fitted pod store")
        val bombs = AircraftTargetingPods.effective(definition, { "fixture:bomb" }, stores::get) { false }
        assertNull(bombs, "other stores give no pod")
        val fitted = mapOf("centre" to "fixture:bomb", "wing" to podId)
        val pod = AircraftTargetingPods.effective(definition, fitted::get, stores::get) { false }!!
        assertEquals("wing", pod["Mount"].asString)
        assertEquals(0, pod["MountPosition"].asInt, "left pod first")
        // left wing lost: the right pod takes over; both lost: no pod
        val right = AircraftTargetingPods.effective(definition, fitted::get, stores::get) { it.x > 0 }!!
        assertEquals(1, right["MountPosition"].asInt)
        assertNull(AircraftTargetingPods.effective(definition, fitted::get, stores::get) { true })
        // an aircraft with its own pod keeps it, pod store or not
        val builtIn = json("""{"Position":[0,1,6],"YawLimit":180,"PitchMin":-10,"PitchMax":90,"Source":"built in"}""")
        val withPod = definition.deepCopy().apply { add("Pod", builtIn) }
        assertEquals(builtIn, AircraftTargetingPods.effective(withPod, fitted::get, stores::get) { false })
        // a selection the station does not allow is ignored
        assertNull(AircraftTargetingPods.effective(jet(), fitted::get, stores::get) { false })
    }

    @Test fun `a pod on a swept pylon follows the wing`() {
        val definition = jet().apply {
            getAsJsonArray("Pairs")[0].asJsonObject.add("SweepFrames", JsonParser.parseString("""[
                {"Pivot":[1,1,0],"Axis":[0,1,0],"MaxDegrees":30,"Points":[[0,0],[2,1]]},
                {"Pivot":[-1,1,0],"Axis":[0,-1,0],"MaxDegrees":30,"Points":[[0,0],[2,1]]}]"""))
        }
        val offered = AircraftTargetingPods.withUniversalStores(definition, stores())
        val wing = AircraftArmamentRegistry.mounts(offered).first { it["Id"].asString == "wing" }
        val pod = AircraftTargetingPods.storePod(wing, podStore(), podId, 0)
        val still = AircraftTargetingPods.sweptPosition(offered, pod, 0.0)!!
        near(AircraftArmamentRegistry.vector(pod["Position"])!!, still, "unswept at rest")
        val swept = AircraftTargetingPods.sweptPosition(offered, pod, 5.0)!!
        near(AircraftMountSweep.position(wing, 0, still, 5.0), swept, "same offset as the pylon's launches")
        assertTrue(swept.distanceTo(still) > 0.5)
        val builtIn = json("""{"Position":[0,1,6],"YawLimit":180,"PitchMin":-10,"PitchMax":90,"Source":"built in"}""")
        near(Vec3(0.0, 1.0, 6.0), AircraftTargetingPods.sweptPosition(offered, builtIn, 5.0), "built-in pods never move")
    }

    @Test fun `client receipt carries the store pod and rejects one naming an unknown station`() {
        val definition = AircraftTargetingPods.withUniversalStores(jet(), stores())
        val wing = AircraftArmamentRegistry.mounts(definition).first { it["Id"].asString == "wing" }
        fun receipt(pod: JsonObject) = JsonObject().apply {
            addProperty("Vehicle", "00000000-0000-0000-0000-000000000001"); addProperty("EntityId", 7); addProperty("Revision", 1)
            add("Definition", definition.deepCopy().apply { add("Pod", pod) })
            add("Stores", JsonObject().apply { add(podId, podStore()); add("fixture:bomb", bomb()) })
            add("Selections", JsonObject().apply { addProperty("wing", podId) })
            addProperty("PodActive", true)
        }
        val pod = AircraftTargetingPods.storePod(wing, podStore(), podId, 1)
        val snapshot = AircraftArmamentSnapshot.decode(receipt(pod))!!
        val view = snapshot.definition.pod!!
        assertEquals("wing", view.mount); assertEquals(1, view.mountPosition)
        near(AircraftTargetingPods.eye(wing, podStore(), podId, 1), view.position)
        assertTrue(snapshot.podActive)
        assertEquals("Targeting pod", snapshot.stores.getValue(podId).categoryLabel)
        assertFalse(snapshot.stores.getValue(podId).visualOnly)
        assertNull(AircraftArmamentSnapshot.decode(receipt(pod.deepCopy().apply { addProperty("Mount", "nowhere") })))
        assertNull(AircraftArmamentSnapshot.decode(receipt(pod.deepCopy().apply { addProperty("MountPosition", 2) })))
    }

    @Test fun `a pod store is free to fit and never a weapon row`() {
        assertNull(AircraftLoadoutCost.ammoId(podStore()))
        assertFalse(AircraftStoreWeapons.launchable(podStore()))
        assertTrue(AircraftWeaponGroups.groups(listOf(AircraftWeaponGroups.Mount("wing", podId, "TARGETING_POD",
            listOf("S8KOPylon1")))).isEmpty())
    }

    // --------------------------------------------------------------------------------- generated pack data
    private val root = File(System.getProperty("bvp.sbwData") ?: "../bvp/src/generated/resources/data/berts_vehicle_pack/sbw")

    @Test fun `the Palantir pod fits every external pylon of every jet in the pack`() {
        val file = File(root, "aircraft_stores/sensor/palantir_pod.json")
        assumeTrue(file.isFile, "pack data not beside this project")
        val palantir = JsonParser.parseString(file.readText()).asJsonObject
        AircraftArmamentRegistry.validate(palantir, true)
        val id = "berts_vehicle_pack:sensor/palantir_pod"
        val catalogue = mapOf(ResourceLocation(id) to palantir)
        val problems = mutableListOf<String>()
        var pylons = 0
        for (arm in File(root, "aircraft_armaments").listFiles().orEmpty().filter { it.extension == "json" }) {
            val vehicle = File(root, "vehicles/${arm.name}")
            val type = if (vehicle.isFile) JsonParser.parseString(vehicle.readText()).asJsonObject["Type"]?.asString else null
            if (type != "Airplane") continue
            val raw = JsonParser.parseString(arm.readText()).asJsonObject
            val definition = AircraftTargetingPods.withUniversalStores(raw, catalogue)
            for (mount in AircraftArmamentRegistry.mounts(definition)) {
                val offered = id in (mount.getAsJsonArray("AllowedStores")?.map { it.asString } ?: emptyList())
                val pylon = mount["Internal"]?.asBoolean != true && mount["Id"].asString != "bay_gun"
                if (offered != pylon) problems += "${arm.nameWithoutExtension}/${mount["Id"].asString}: offered=$offered"
                if (!pylon) continue
                pylons++
                val limit = mount["MaxPylonMassKg"]?.asDouble ?: definition["MaxPylonMassKg"]?.asDouble ?: Double.MAX_VALUE
                if (palantir["MassKg"].asDouble > limit) problems += "${arm.nameWithoutExtension}/${mount["Id"].asString}: $limit kg pylon"
                val pair = AircraftArmamentRegistry.loadoutMassKg(definition, mapOf(mount["Id"].asString to palantir))
                if (pair > definition["MaxPayloadKg"].asDouble) problems += "${arm.nameWithoutExtension}: payload"
                // the eye is ahead of the station and below the pylon, never inside the wing above it
                val eye = AircraftTargetingPods.eye(mount, palantir, id, 0)
                val station = AircraftPylonRacks.launchPosition(mount, palantir, 1, 0, id)
                if (eye.z - station.z !in 1.0..1.4 || eye.y >= station.y) problems += "${arm.nameWithoutExtension}: eye $eye"
            }
            runCatching { AircraftArmamentRegistry.validate(definition, false) }
                .onFailure { problems += "${arm.name}: ${it.message}" }
        }
        assertTrue(pylons > 100, "jet pylons checked: $pylons")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
