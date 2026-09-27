package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ProjectileBeltTest {
    private fun round(id: String, shots: Int = 1, tracer: ProjectileBeltTracer = ProjectileBeltTracer.NONE) =
        ProjectileBeltRound(shots, "fixture:$id", tracer, id)

    @Test fun `NR30 family decodes without dropping the weapon belt`() {
        val belt = Json.decodeFromString<ProjectileBeltProfile>("""
            {"Family":"NR30","Name":"Air Belt","Rounds":[
                {"Shots":1,"Round":"ap","Ammo":"fixture:ap","Tracer":"NONE"},
                {"Shots":1,"Round":"he","Ammo":"fixture:he","Tracer":"INHERIT"},
                {"Shots":1,"Round":"ap","Ammo":"fixture:ap","Tracer":"NONE"}
            ]}
        """.trimIndent())
        assertEquals(ProjectileBeltFamily.NR30, belt.family)
        assertTrue(belt.isValid())
        assertEquals(listOf("ap", "he", "ap"), (0..2).map { belt.roundAt(it)?.roundId })
        assertEquals(ProjectileBeltTracer.INHERIT, belt.roundAt(1)?.tracer)
        assertEquals(belt.roundAt(0), belt.roundAt(3))
    }

    @Test fun `NR30 Air belt reuses one HEFI owner and traces only HEFIT`() {
        val belt = Json.decodeFromString<ProjectileBeltProfile>("""
            {"Name":"Air Belt","Family":"NR30","Rounds":[
                {"Shots":1,"Round":"nr30_aphe","Ammo":"superbwarfare:small_shell_ap","Tracer":"NONE"},
                {"Shots":1,"Round":"nr30_hef_i","Ammo":"superbwarfare:small_shell_he","Tracer":"NONE"},
                {"Shots":1,"Round":"nr30_hef_i","Ammo":"superbwarfare:small_shell_he","Tracer":"NONE"},
                {"Shots":1,"Round":"nr30_hefi_t","Ammo":"superbwarfare:small_shell_aa","Tracer":"RED"}
            ]}
        """.trimIndent())
        assertTrue(belt.isValid())
        assertEquals(ProjectileBeltFamily.NR30, belt.family)
        assertEquals(4, belt.cycleLength())
        val expected = listOf("nr30_aphe", "nr30_hef_i", "nr30_hef_i", "nr30_hefi_t")
        for (phase in 0..11) {
            assertEquals(expected[phase % 4], belt.roundAt(phase)?.roundId)
            assertEquals(if (phase % 4 == 3) ProjectileBeltTracer.RED else ProjectileBeltTracer.NONE,
                belt.roundAt(phase)?.tracer)
        }
        assertEquals(3, (0..3).map { belt.roundAt(it)?.ammo }.toSet().size)
        assertEquals(belt.roundAt(1), belt.roundAt(2))
    }

    @Test fun `War Thunder tracer colours decode per round`() {
        val belt = Json.decodeFromString<ProjectileBeltProfile>("""
            {"Name":"Mixed","Rounds":[
                {"Shots":1,"Round":"a","Ammo":"fixture:a","Tracer":"WHITE"},
                {"Shots":1,"Round":"b","Ammo":"fixture:b","Tracer":"LIGHT_RED"},
                {"Shots":1,"Round":"c","Ammo":"fixture:c","Tracer":"BRIGHT_RED"},
                {"Shots":1,"Round":"d","Ammo":"fixture:d","Tracer":"DARK_RED"},
                {"Shots":1,"Round":"e","Ammo":"fixture:e","Tracer":"PINK"}
            ]}
        """.trimIndent())
        assertTrue(belt.isValid())
        assertEquals(listOf(ProjectileBeltTracer.WHITE, ProjectileBeltTracer.LIGHT_RED, ProjectileBeltTracer.BRIGHT_RED,
            ProjectileBeltTracer.DARK_RED, ProjectileBeltTracer.PINK), (0..4).map { belt.roundAt(it)?.tracer })
    }

    @Test fun `Bradley APDS and HE belts preserve exact four shot ordering`() {
        val ap = round("m791_apds", 3)
        val he = round("m792_hei_t_1", tracer = ProjectileBeltTracer.INHERIT)
        val apBelt = ProjectileBeltProfile(listOf(ap, he), "APDS Belt", ProjectileBeltFamily.M242)
        val heBelt = ProjectileBeltProfile(listOf(he.copy(shots = 3), ap.copy(shots = 1)), "HE Belt", ProjectileBeltFamily.M242)
        assertEquals(listOf("m791_apds", "m791_apds", "m791_apds", "m792_hei_t_1"), (0..3).map { apBelt.roundAt(it)?.roundId })
        assertEquals(listOf("m792_hei_t_1", "m792_hei_t_1", "m792_hei_t_1", "m791_apds"), (0..3).map { heBelt.roundAt(it)?.roundId })
        assertEquals(ProjectileBeltTracer.INHERIT, apBelt.roundAt(3)?.tracer)
    }

    @Test fun `same round can occur at multiple separated belt positions`() {
        val belt = ProjectileBeltProfile(listOf(round("api_t"), round("iai"), round("api_t"), round("cermet")), "12.7mm Belt", ProjectileBeltFamily.RUSSIAN_127)
        assertTrue(belt.isValid())
        assertEquals(4, belt.cycleLength())
        assertEquals(belt.roundAt(0), belt.roundAt(2))
        assertEquals(belt.roundAt(0), belt.roundAt(4))
        assertEquals("cermet", belt.roundAt(-1)?.roundId)
    }

    @Test fun `family and cosmetic edits do not silently disable firing`() {
        val he = round("new_he_round", 5, ProjectileBeltTracer.INHERIT)
        for (family in ProjectileBeltFamily.entries) {
            val belt = ProjectileBeltProfile(listOf(he.copy(displayLabel = "new effect schema")), "Custom belt", family)
            assertTrue(belt.isValid(), family.name)
            assertEquals(5, belt.cycleLength())
            assertEquals(ProjectileBeltTracer.INHERIT, belt.roundAt(3)?.tracer)
        }
    }

    @Test fun `compiled cycle snapshots caller list without per-shot revalidation`() {
        val source = mutableListOf(round("apds"), round("he"))
        val belt = ProjectileBeltProfile(source, "APDS Belt")
        source.clear()
        val first = belt.roundAt(0)
        assertEquals(2, belt.cycleLength())
        assertSame(first, belt.roundAt(2))
    }

    @Test fun `invalid references and oversized cycles still fail closed`() {
        assertFalse(ProjectileBeltProfile(listOf(ProjectileBeltRound(ammo = null)), "Named").isValid())
        assertFalse(ProjectileBeltProfile(listOf(round("he", 0)), "Named").isValid())
        assertFalse(ProjectileBeltProfile(listOf(round("he", 1025)), "Named").isValid())
        assertFalse(ProjectileBeltProfile(List(5) { round("he", 1024) }, "Named").isValid())
        assertFalse(ProjectileBeltProfile(listOf(round("he").copy(ammo = " \n")), "Named").isValid())
    }

    @Test fun `serialization rebuilds the derived cycle without serializing cache internals`() {
        val belt = ProjectileBeltProfile(listOf(round("apds", 3), round("he")), "APDS Belt")
        val encoded = Json.encodeToString(belt)
        assertFalse(encoded.contains("compiled"))
        val decoded = Json.decodeFromString<ProjectileBeltProfile>(encoded)
        assertEquals(4, decoded.cycleLength())
        assertEquals(listOf("apds", "apds", "apds", "he"), (0..3).map { decoded.roundAt(it)?.roundId })
    }
}
