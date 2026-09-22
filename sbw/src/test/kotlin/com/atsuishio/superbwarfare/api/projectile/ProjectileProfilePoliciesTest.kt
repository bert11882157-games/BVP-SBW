package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.gun.ProjectileBeltTracer
import com.atsuishio.superbwarfare.data.projectile.MotionSyncPolicy
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectileProfilePoliciesTest {
    private val id = ResourceLocation("fixture", "round")
    private val providerId = ResourceLocation("fixture", "presentation_policy")
    private fun template() = ResolvedProjectileProfile(id,
        ProjectileCombatDescriptor(id, id, id, id, id, 7.62, 9.0, false,
            hullDamageClass = ProjectileHullDamageClass.DEFAULT, hullDamage = 5, moduleDamage = 3, ammoRackDamage = 1),
        id, id, MotionSyncPolicy.INHERIT, ResolvedProjectileCollision(0.006f, 0.006f), 8,
        ProjectileTrailMode.DEFAULT, 1f, JsonObject())

    private fun policy(presentation: (ProjectilePresentationInput) -> ProjectilePresentationPatch?) =
        object : ProjectileProfilePolicy {
            override fun presentation(input: ProjectilePresentationInput) = presentation.invoke(input)
            override fun roundMatches(query: ProjectileRoundQuery, roundId: ResourceLocation) = false
            override fun terrain(input: ProjectileTerrainInput) = ProjectileTerrainDecision.INHERIT
        }

    @Test fun `without addons native profile and terrain policy remain unchanged`() {
        val template = template()
        assertSame(template, ProjectileProfiles.shotTracerProfile(template, ProjectileBeltTracer.INHERIT))
        assertSame(template, ProjectileProfiles.shotTracerProfile(template, ProjectileBeltTracer.GREEN))
        assertFalse(ProjectileProfilePolicies.roundMatches(ProjectileRoundQuery.CYCLIC_145, id))
        assertEquals(ProjectileTerrainDecision.INHERIT,
            ProjectileProfilePolicies.terrain(ProjectileTerrainInput(id, id, true)))
    }

    @Test fun `suppress-all belt policy cannot be overridden by an addon presentation patch`() {
        ProjectileProfilePolicies.register(providerId, policy {
            ProjectilePresentationPatch(ProjectileTrailMode.REPLACE, 2f, JsonObject())
        })
        try {
            for (policy in listOf(ProjectileBeltTracer.NONE, ProjectileBeltTracer.SUPPRESS)) {
                assertEquals(ProjectileTrailMode.SUPPRESS, ProjectileProfiles.shotTracerProfile(template(), policy).trailMode)
            }
        } finally { ProjectileProfilePolicies.unregister(providerId) }
    }

    @Test fun `patch owns defensive extension copies and cannot replace gameplay fields`() {
        val extensions = JsonObject().apply { addProperty("fixture:tracer", "white") }
        val patch = ProjectilePresentationPatch(ProjectileTrailMode.REPLACE, 0.2f, extensions)
        extensions.addProperty("fixture:tracer", "changed")
        patch.extensions().addProperty("fixture:tracer", "also changed")
        val source = template()
        val result = ProjectileProfiles.presentationSnapshot(source, patch, source.trailMode, false)
        assertEquals("white", result.extensions().get("fixture:tracer").asString)
        assertTrue(source.extensions().entrySet().isEmpty())
        assertSame(source.combat, result.combat)
        assertSame(source.collision, result.collision)
        assertSame(source.motionSync, result.motionSync)
        assertEquals(source.id, result.id)
        assertEquals(source.luminance, result.luminance)
        assertEquals(source.visualProfileId, result.visualProfileId)
    }

    @Test fun `missing or failed visual policy retains fragment combat and clears impact presentation`() {
        val source = template()
        ProjectileProfilePolicies.register(providerId, policy { error("unavailable visual template") })
        try {
            for (scale in listOf(1f, Float.NaN, -1f)) {
                val result = ProjectileProfiles.impactFragmentProfile(source, scale)
                assertSame(source.combat, result.combat)
                assertSame(source.collision, result.collision)
                assertEquals(5, result.combat!!.hullDamage)
                assertEquals(3, result.combat!!.moduleDamage)
                assertEquals(1, result.combat!!.ammoRackDamage)
                assertNull(result.impactVisualProfileId)
                assertEquals(ProjectileTrailMode.SUPPRESS, result.trailMode)
            }
        } finally { ProjectileProfilePolicies.unregister(providerId) }
    }

    @Test fun `optional render scale never vetoes valid fragment gameplay inputs`() {
        val source = template()
        val spec = ImpactFragmentSpawnSpec(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 1f, 5, 1f,
            source, Float.NaN, 0)
        assertTrue(spec.isValid())
        assertTrue(spec.copy(lifetimeTicks = 16).isValid())
        assertFalse(spec.copy(lifetimeTicks = 17).isValid())
        assertFalse(spec.copy(direction = Vec3.ZERO).isValid())
        assertFalse(spec.copy(damage = Float.NaN).isValid())
        assertFalse(spec.copy(position = Vec3(Double.NaN, 0.0, 0.0)).isValid())
    }

    @Test fun `registry replacement and unregister preserve deterministic provider order`() {
        val second = ResourceLocation("fixture", "second")
        val seen = mutableListOf<String>()
        ProjectileProfilePolicies.register(providerId, policy { seen += "first"; null })
        ProjectileProfilePolicies.register(second, policy {
            seen += "second"; ProjectilePresentationPatch(ProjectileTrailMode.REPLACE, 2f, JsonObject())
        })
        try {
            val input = ProjectilePresentationInput(template(), ProjectilePresentationPurpose.SHOT_TRACER,
                ProjectileBeltTracer.RED, 1f)
            assertEquals(2f, ProjectileProfilePolicies.presentation(input)!!.renderScale)
            assertEquals(listOf("first", "second"), seen)
            assertTrue(ProjectileProfilePolicies.unregister(providerId))
            seen.clear()
            ProjectileProfilePolicies.presentation(input)
            assertEquals(listOf("second"), seen)
        } finally {
            ProjectileProfilePolicies.unregister(providerId)
            ProjectileProfilePolicies.unregister(second)
        }
    }

    @Test fun `shared profile mechanism has no embedded pack identities or effect schema keys`() {
        javaClass.classLoader.getResourceAsStream(
            "com/atsuishio/superbwarfare/api/projectile/ProjectileProfiles.class")!!.use {
            val constants = String(it.readBytes(), Charsets.ISO_8859_1)
            assertFalse(constants.contains("berts_vehicle_pack"))
            assertFalse(constants.contains("ColorRgb"))
            assertFalse(constants.contains("projectile_effect_v1"))
        }
    }
}
