package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class AircraftHelmetSightTest {
    private val nose = Vec3(0.0, 0.0, 1.0)
    /** A direction [degrees] to the right of the nose, level. */
    private fun right(degrees: Double) = Math.toRadians(degrees).let { Vec3(-sin(it), 0.0, cos(it)) }
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    private val r73 = json("""{"Category":"AIR_TO_AIR","Guidance":{"Mode":"INFRARED","LockTicks":45,"Range":1100,
        "ConeDegrees":40,"CountermeasureVulnerability":0.75,"HelmetOffBoresightDegrees":45}}""")
    private val r27t = json("""{"Category":"AIR_TO_AIR","Guidance":{"Mode":"INFRARED","LockTicks":60,"Range":1100,
        "ConeDegrees":30,"CountermeasureVulnerability":0.75}}""")
    private val mig29 = json("""{"Schema":1,"Name":"MiG-29","HelmetSight":{"Name":"Shchel-3UM","MaxOffBoresightDegrees":60}}""")
    private val mig23 = json("""{"Schema":1,"Name":"MiG-23MLD"}""")

    @Test fun `both the aircraft and the seeker must opt in and the smaller limit wins`() {
        assertEquals(45.0, AircraftHelmetSight.limit(mig29, r73))
        assertNull(AircraftHelmetSight.limit(mig23, r73), "no helmet sight on the aircraft")
        assertNull(AircraftHelmetSight.limit(mig29, r27t), "seeker cannot be cued")
        val jhmcs = json("""{"HelmetSight":{"Name":"JHMCS","MaxOffBoresightDegrees":30}}""")
        assertEquals(30.0, AircraftHelmetSight.limit(jhmcs, r73))
    }

    @Test fun `looking near the nose keeps the ordinary boresight seeker`() {
        assertNull(AircraftHelmetSight.line(nose, right(1.5), null, 45.0, 40.0))
        assertNotNull(AircraftHelmetSight.line(nose, right(2.5), null, 45.0, 40.0), "2 degrees off the crosshair cues")
        assertNull(AircraftHelmetSight.line(nose, null, null, 45.0, 40.0))
    }

    @Test fun `looking away slaves the seeker to the helmet with the narrow circle`() {
        val line = AircraftHelmetSight.line(nose, right(30.0), null, 45.0, 40.0)!!
        assertEquals(0.0, AircraftHelmetSight.angleDegrees(line.forward, right(30.0)), 1e-9)
        assertEquals(AircraftHelmetSight.ACQUIRE_CONE_DEGREES, line.coneDegrees)
    }

    @Test fun `looking past the limit does not cue`() {
        assertNull(AircraftHelmetSight.line(nose, right(50.0), null, 45.0, 40.0))
    }

    @Test fun `a held target is tracked inside the limit wherever the pilot looks`() {
        val target = right(35.0).scale(800.0)
        val looking = AircraftHelmetSight.line(nose, right(33.0), target, 45.0, 40.0)!!
        assertEquals(0.0, AircraftHelmetSight.angleDegrees(looking.forward, target), 1e-9)
        assertEquals(40.0, looking.coneDegrees)
        val glancedBack = AircraftHelmetSight.line(nose, null, target, 45.0, 40.0)!!
        assertEquals(0.0, AircraftHelmetSight.angleDegrees(glancedBack.forward, target), 1e-9)
    }

    @Test fun `looking at another aircraft re-cues and a target past the limit is let go`() {
        val target = right(35.0)
        val recue = AircraftHelmetSight.line(nose, right(-20.0), target, 45.0, 40.0)!!
        assertEquals(0.0, AircraftHelmetSight.angleDegrees(recue.forward, right(-20.0)), 1e-9)
        assertEquals(AircraftHelmetSight.ACQUIRE_CONE_DEGREES, recue.coneDegrees)
        assertNull(AircraftHelmetSight.line(nose, null, right(60.0), 45.0, 40.0))
    }

    @Test fun `validation rejects a cue on anything but an air-to-air seeker`() {
        assertThrows(IllegalArgumentException::class.java) {
            AircraftHelmetSight.validateStore(json("""{"Category":"AIR_TO_GROUND","Guidance":{"HelmetOffBoresightDegrees":45}}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AircraftHelmetSight.validateSight(json("""{"HelmetSight":{"Name":"X","MaxOffBoresightDegrees":400}}"""))
        }
        AircraftHelmetSight.validateStore(r73)
        AircraftHelmetSight.validateSight(mig29)
    }

    /**
     * Only aircraft that really carry a helmet sight, and only seekers that really take a helmet cue, opt in (owner
     * 2026-11-01). A change to either roster must be a deliberate edit here. Skipped without the pack data beside sbw/.
     */
    @Test fun `pack helmet rosters are exactly the real-world ones`() {
        val root = File(System.getProperty("bvp.sbwData") ?: "../bvp/src/generated/resources/data/berts_vehicle_pack/sbw")
        assumeTrue(File(root, "aircraft_armaments").isDirectory, "pack data not beside this project")
        val aircraft = File(root, "aircraft_armaments").listFiles().orEmpty().filter { it.extension == "json" }
            .filter { json(it.readText()).has(AircraftHelmetSight.SIGHT) }.map { it.nameWithoutExtension }.toSortedSet()
        assertEquals(sortedSetOf("eurofighter_typhoon", "f_15c", "f_15e", "f_15ex", "f_16c", "fa_18c", "fa_18e", "fc_31",
            "j_10a", "j_11a", "j_15d", "mig_29", "saab_jas_39_gripen", "su_27", "su_30", "su_35"), aircraft)
        val stores = File(root, "aircraft_stores").walkTopDown().filter { it.isFile && it.extension == "json" }
            .filter { runCatching { AircraftHelmetSight.missileLimit(json(it.readText())) }.getOrNull() != null }
            .map { it.relativeTo(File(root, "aircraft_stores")).path.removeSuffix(".json").replace('\\', '/') }.toSortedSet()
        assertEquals(sortedSetOf("fa18e/aim9m", "fa18e/aim9m_x2", "munition/aim9m_x2_lau105", "munition/aim9x",
            "munition/asraam", "munition/iris_t", "munition/pl10", "munition/pl8", "r73", "r73_x2"), stores)
    }
}
