package com.atsuishio.superbwarfare.world.phys

import com.atsuishio.superbwarfare.tools.OBB
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult

class ProjectileContactRegressionTest {
    private val target = UUID(0, 1)

    @Test fun `direct contact affects its selected side only on its own target and tick`() {
        for (part in listOf(OBB.Part.WHEEL_LEFT, OBB.Part.WHEEL_RIGHT, OBB.Part.BODY)) {
            val contact = ProjectileContact(target, 40, part)
            assertEquals(part, contact.partFor(target, 40, false))
            assertEquals(OBB.Part.EMPTY, contact.partFor(UUID(0, 2), 40, false))
            assertEquals(OBB.Part.EMPTY, contact.partFor(target, 41, false))
        }
    }

    @Test fun `splash on both struck and neighboring vehicles cannot reuse a track contact`() {
        for (part in listOf(OBB.Part.WHEEL_LEFT, OBB.Part.WHEEL_RIGHT)) {
            val contact = ProjectileContact(target, 40, part)
            for (victim in listOf(target, UUID(0, 2))) {
                assertEquals(OBB.Part.EMPTY, contact.partFor(victim, 40, true))
            }
            // Rejecting splash must not destroy the direct hit receipt (bullet splash runs first).
            assertEquals(part, contact.partFor(target, 40, false))
        }
    }

    @Test fun `passed contacts still dispatch the terrain endpoint exactly once`() {
        val visited = mutableListOf<String>()
        ProjectileSweepTraversal.visit(listOf("pass vehicle", "pass entity"), {
            visited += it
            true
        }, { visited += "block" })
        assertEquals(listOf("pass vehicle", "pass entity", "block"), visited)
    }

    @Test fun `blocked consumed or removed projectile cannot reach later entity or terrain`() {
        for (stop in listOf("BLOCK", "CONSUME", "removed", "native penetration budget exhausted")) {
            val visited = mutableListOf<String>()
            ProjectileSweepTraversal.visit(listOf("PASS", stop, "later target"), {
                visited += it
                it == "PASS"
            }, { visited += "block" })
            assertEquals(listOf("PASS", stop), visited)
        }
    }

    @Test fun `empty entity sweep still dispatches its block endpoint`() {
        var count = 0
        ProjectileSweepTraversal.visit(emptyList<String>(), { fail("no entity callback expected") }, { count++ })
        assertEquals(1, count)
    }

    @Test fun `ricochet PASS stops old trajectory without consuming the reflected projectile`() {
        val pass = ProjectileImpactResult.builder(ProjectileImpactDisposition.PASS).build()
        val ricochet = ProjectileImpactResult.builder(ProjectileImpactDisposition.PASS)
            .presentationOutcome(ProjectileImpactPresentationOutcome.RICOCHET).build()
        assertFalse(ProjectileSweepTraversal.stops(pass))
        assertTrue(ProjectileSweepTraversal.stops(ricochet))
        assertFalse(ricochet.consumesProjectile())
        for (disposition in listOf(ProjectileImpactDisposition.BLOCK, ProjectileImpactDisposition.CONSUME)) {
            assertTrue(ProjectileSweepTraversal.stops(ProjectileImpactResult.builder(disposition).build()))
        }
    }
}
