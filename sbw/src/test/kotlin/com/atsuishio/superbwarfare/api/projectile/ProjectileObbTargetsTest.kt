package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class ProjectileObbTargetsTest {
    private fun box(x: Double, z: Double, yaw: Double = 0.0) =
        OBB(Vector3d(x, 64.0, z), Vector3d(6.0, 1.5, 2.0), Quaterniond().rotateY(yaw), OBB.Part.EMPTY)

    @Test fun `envelope contains every box corner regardless of rotation`() {
        val obbs = listOf(box(12.0, 0.0), box(-3.0, 9.0, 0.7), box(0.0, -20.0, 2.1))
        val radius = ProjectileObbTargets.envelopeRadius(0.0, 64.0, 0.0, obbs)
        for (obb in obbs) for (corner in obb.getVertices()) {
            val dx = corner.x; val dy = corner.y - 64.0; val dz = corner.z
            assertTrue(sqrt(dx * dx + dy * dy + dz * dz) <= radius + 1.0E-9)
        }
    }

    @Test fun `sphere broadphase is conservative for every query that collides with a box`() {
        val obb = box(30.0, 5.0, 0.4)
        val origin = Vector3d(0.0, 64.0, 0.0)
        val radius = ProjectileObbTargets.envelopeRadius(origin.x, origin.y, origin.z, listOf(obb))
        for (x in -10..50 step 2) for (z in -20..30 step 2) {
            val query = AABB(x.toDouble(), 63.0, z.toDouble(), x + 1.5, 64.5, z + 1.5)
            if (OBB.isColliding(obb, query)) {
                assertTrue(ProjectileObbTargets.sphereIntersects(query, origin.x, origin.y, origin.z, radius))
            }
        }
        assertFalse(ProjectileObbTargets.sphereIntersects(AABB(200.0, 63.0, 0.0, 201.0, 64.0, 1.0),
            origin.x, origin.y, origin.z, radius))
        assertTrue(ProjectileObbTargets.sphereIntersects(AABB(-1.0, 60.0, -1.0, 1.0, 70.0, 1.0), 0.0, 64.0, 0.0, 0.0),
            "A query containing the centre always intersects")
    }
}
