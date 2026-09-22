package com.atsuishio.superbwarfare.client.renderer.vehicle

import com.atsuishio.superbwarfare.resource.vehicle.RunningGearResource
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class RunningGearRigidPathTest {
    private val fixtures = javaClass.getResourceAsStream("/vehicle/fitted_ground_tracks.json")!!.reader().use {
        JsonParser.parseReader(it).asJsonArray.map { entry -> entry.asJsonObject }
    }

    private fun profile(json: JsonObject): TrackRenderProfile {
        val raw = Gson().fromJson(json, RunningGearResource.TrackRender::class.java)
        val method = RunningGearProfiles::class.java.getDeclaredMethod("validateTrack", RunningGearResource.TrackRender::class.java)
        method.isAccessible = true
        return try {
            method.invoke(RunningGearProfiles, raw) as TrackRenderProfile
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.cause!!
        }
    }

    @Test fun `fitted runtime paths preserve center orientation and length over a complete cycle`() {
        for (fixture in fixtures) {
            val id = fixture["id"].asString
            val profile = profile(fixture.getAsJsonObject("runningGear").getAsJsonObject("TrackRender"))
            val vertices = fixture.getAsJsonArray("templateVertices").map { point ->
                point.asJsonArray.map { it.asDouble }
            }
            val length = vertices.maxOf { it[2] } - vertices.minOf { it[2] }
            assertEquals(fixture["sourceLength"].asDouble, length, 0.0002, id)
            val phases = (0..2000).map { it / 20F } + fixture["groundWitnessPhase"].asFloat
            for (side in RunningGearSide.entries) {
                var minimum = Double.POSITIVE_INFINITY
                for (phase in phases) {
                    val index = 7
                    val initial = RunningGearTrackEvaluator.sample(profile, side, index * profile.phaseDistance)
                    val target = RunningGearTrackEvaluator.sample(profile, side, phase + index * profile.phaseDistance)
                    val pose = RunningGearTrackEvaluator.linkPose(profile, side, index, phase)
                    assertEquals(target.y, initial.y + pose.moveY, 0.0001F, id)
                    assertEquals(target.z, initial.z + pose.moveZ, 0.0001F, id)
                    assertEquals(target.rotationXDegrees, pose.rotationXDegrees, 0.0001F, id)
                    assertEquals(1F, pose.longitudinalScale, 0F, id)
                    // Bedrock's local X pose is the inverse of the source-model mathematical rotation.
                    val angle = Math.toRadians(-pose.rotationXDegrees.toDouble())
                    for (point in vertices) minimum = minOf(minimum,
                        target.y + point[1] * cos(angle) - point[2] * sin(angle))
                }
                assertTrue(minimum >= -0.0005, "$id $side track crossed its fitted ground plane: $minimum")
                assertTrue(minimum < 0.01, "$id $side no longer reaches the fitted ground plane: $minimum")
            }
            for (index in 0 until profile.linkCount) {
                val rest = RunningGearTrackEvaluator.linkPose(profile, index, 0F)
                assertEquals(0F, rest.moveY, 0F, id)
                assertEquals(0F, rest.moveZ, 0F, id)
                assertEquals(1F, rest.longitudinalScale, 0F, id)
            }
        }
    }

    @Test fun `rigid path input rejects unknown policies partial cycles and deformation`() {
        val raw = fixtures.first().getAsJsonObject("runningGear").getAsJsonObject("TrackRender")
        for (value in listOf("UNKNOWN", "", "RIGID")) {
            val changed = raw.deepCopy().apply { addProperty("LinkFit", value) }
            assertThrows(IllegalArgumentException::class.java) { profile(changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { profile(raw.deepCopy().apply { add("LinkFit", null) }) }
        assertThrows(IllegalArgumentException::class.java) { profile(raw.deepCopy().apply { addProperty("TravelScale", 0.5F) }) }
        assertThrows(IllegalArgumentException::class.java) { profile(raw.deepCopy().apply { addProperty("PhaseDistance", 1F) }) }
        val legacy = profile(raw.deepCopy().apply { remove("LinkFit") })
        assertEquals(TrackLinkFit.CONTACT_INTERVAL, legacy.linkFit)
        val rigid = profile(raw)
        assertTrue((0 until rigid.linkCount).any {
            abs(RunningGearTrackEvaluator.linkPose(legacy, it, 3F).longitudinalScale - 1F) > 0.001F
        }, "Fixture must distinguish rigid source lengths from legacy contact fitting")
    }
}
