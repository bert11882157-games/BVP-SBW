package com.atsuishio.superbwarfare.client.sound.vehicle

import com.atsuishio.superbwarfare.client.sound.spatial.SpatialAudioPlayer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Aircraft engine audio runs on the authored-profile system (the owner's request, 2026-09-28: the old per-aircraft
 * loops were 2-4 s, shared, clicked at the seam and cut off at 64 blocks). Every BVP aircraft and helicopter has a
 * profile, every sound it names is registered and present, and the spool / propagation helpers behave. The pack
 * checks are skipped when the BVP sources are not beside this project.
 */
class AircraftAudioProfileTest {
    private val bvp = File(System.getProperty("bvp.root") ?: "../bvp")
    private val assets = File(bvp, "src/main/resources/assets")

    private fun json(file: File): JsonObject = file.reader().use { JsonParser.parseReader(it).asJsonObject }

    @Test fun `every BVP aircraft and helicopter has a complete engine profile`() {
        val vehicles = File(bvp, "src/generated/resources/data/berts_vehicle_pack/sbw/vehicles")
        assumeTrue(vehicles.isDirectory && assets.isDirectory, "BVP sources not present")
        val sounds = json(File(assets, "bvp_audio/sounds.json"))
        val aircraft = vehicles.listFiles { f -> f.name.endsWith(".json") }!!
            .filter { json(it).get("Type")?.asString in setOf("Airplane", "Helicopter") }
        assertTrue(aircraft.size >= 90, "expected the whole air fleet, found ${aircraft.size}")
        val missing = ArrayList<String>()
        for (file in aircraft) {
            val name = file.name.removeSuffix(".json")
            val path = File(assets, "berts_vehicle_pack/sbw/vehicle_audio/$name.json")
            if (!path.isFile) { missing += "$name: no profile"; continue }
            val profile = VehicleAudioProfiles.parse(ResourceLocation("berts_vehicle_pack", name), json(path))
            val engine = profile.engine
            if (engine?.idle == null) { missing += "$name: no engine loop"; continue }
            if (profile.distant == null) missing += "$name: no far layer"
            val helicopter = json(file).get("Type").asString == "Helicopter"
            if (!helicopter && engine.spoolUp < 1f) missing += "$name: jet/prop RPM does not spool"
            if (!helicopter && profile.boost == null) missing += "$name: no full-power roar"
            val named = listOfNotNull(engine.start, engine.idle, engine.stop, profile.boost?.loop,
                profile.distant?.loop, profile.rotor?.loop, profile.interior?.loop, profile.afterburner?.loop,
                profile.afterburner?.start, profile.afterburnerBody?.loop, profile.airflow?.loop, profile.flyby?.start)
            // owner 2026-09-29 "we need more whooshing": rushing air on everything, a pass whoosh on every plane
            if (profile.airflow == null || profile.airflow!!.fullSpeed <= 0f) missing += "$name: no airflow"
            if (!helicopter && profile.flyby?.start == null) missing += "$name: no flyby whoosh"
            for (sound in named) {
                if (sound.namespace != "bvp_audio") continue
                if (!sounds.has(sound.path)) missing += "$name: $sound not in sounds.json"
                if (!File(assets, "bvp_audio/sounds/${sound.path}.ogg").isFile) missing += "$name: $sound has no file"
            }
        }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }

    @Test fun `aircraft are heard far beyond the old 64-block cutoff`() {
        val profile = File(assets, "berts_vehicle_pack/sbw/vehicle_audio/f_16c.json")
        assumeTrue(profile.isFile, "BVP sources not present")
        val parsed = VehicleAudioProfiles.parse(ResourceLocation("berts_vehicle_pack", "f_16c"), json(profile))
        assertTrue(parsed.reach >= 1000f, "a jet carries for a kilometre or more, reach ${parsed.reach}")
        assertNotNull(parsed.boost, "afterburning fighters have the low-frequency roar layer")
    }

    /** Owner 2026-09-29: the afterburner must be heard, above the full-power roar, on every jet that has one. */
    @Test fun `every afterburning jet has the afterburner layer and its ignition`() {
        val flight = File(bvp, "src/generated/resources/data/berts_vehicle_pack/flight_reference")
        assumeTrue(flight.isDirectory && assets.isDirectory, "BVP sources not present")
        val problems = ArrayList<String>()
        var burners = 0
        for (file in flight.listFiles { f -> f.name.endsWith(".json") }!!) {
            val name = file.name.removeSuffix(".json")
            val lit = json(file).getAsJsonObject("engineering")?.get("afterburnerEnabled")?.asBoolean == true
            val path = File(assets, "berts_vehicle_pack/sbw/vehicle_audio/$name.json")
            if (!path.isFile) continue
            val profile = VehicleAudioProfiles.parse(ResourceLocation("berts_vehicle_pack", name), json(path))
            val ab = profile.afterburner
            if (lit) {
                burners++
                if (ab == null) { problems += "$name: afterburner without an afterburner sound"; continue }
                if (ab.start == null) problems += "$name: no ignition one-shot"
                if (profile.afterburnerBody == null) problems += "$name: no afterburner rumble voice"
                if (ab.range < (profile.boost?.range ?: 0f)) problems += "$name: burner carries less far than the roar"
                if (ab.volume[0] < 0.8f) problems += "$name: burner layer too quiet"
            } else if (ab != null) problems += "$name: afterburner sound on an aircraft without one"
        }
        assertTrue(burners >= 40, "expected the afterburning fleet, found $burners")
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test fun `RPM spools at the authored rate and snaps without one`() {
        var rpm = 0f
        var ticks = 0
        while (rpm < 1f && ticks < 1000) { rpm = VehicleAudioController.spool(rpm, 1f, 2f, 1f); ticks++ }
        assertEquals(40, ticks, "2 s spool-up = 40 ticks")
        rpm = VehicleAudioController.spool(1f, 0f, 2f, 1f)
        assertEquals(0.95f, rpm, 1e-4f)
        assertEquals(0.3f, VehicleAudioController.spool(0.9f, 0.3f, 0f, 0f))
    }

    @Test fun `a distant source is heard where it was when the sound left it`() {
        val c = SpatialAudioPlayer.SOUND_BLOCKS_PER_TICK
        // parked 343 blocks away: one second (20 ticks) of travel
        assertEquals(20, VehicleAudioController.propagationLag(100) { 343.3 })
        // close by: next to no delay
        assertEquals(0, VehicleAudioController.propagationLag(100) { 4.0 })
        // a jet flying straight away at 5 blocks/tick from 600 blocks: now at 600, k ticks ago at 600 - 5k
        val lag = VehicleAudioController.propagationLag(100) { k -> 600.0 - 5.0 * k }
        assertTrue(abs((600.0 - 5.0 * lag) - lag * c) <= c, "emission point matches travel time, lag $lag")
        // beyond the history: the oldest sample
        assertEquals(99, VehicleAudioController.propagationLag(100) { 5000.0 })
    }
}
