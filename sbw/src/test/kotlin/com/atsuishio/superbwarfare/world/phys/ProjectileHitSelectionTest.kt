package com.atsuishio.superbwarfare.world.phys

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProjectileHitSelectionTest {
    private val start = Vec3.ZERO
    private val end = Vec3(40.0, 0.0, 0.0)
    private fun box(x: Double, part: OBB.Part, yaw: Double = 0.0) =
        OBB(Vector3d(x, 0.0, 0.0), Vector3d(1.0, 1.0, 1.0), Quaterniond().rotateY(yaw), part)

    @Test
    fun `shell reducer always selects front entity and its part in either candidate order`() {
        val front = box(10.0, OBB.Part.TURRET)
        val rear = box(30.0, OBB.Part.MAIN_ENGINE)
        for (candidates in listOf(listOf("rear" to rear, "front" to front), listOf("front" to front, "rear" to rear))) {
            val query = ProjectileHitSelection.Nearest<String>(start, start.distanceToSqr(end))
            for ((name, obb) in candidates) {
                val hit = ProjectileHitSelection.nearestObb(listOf(obb), start, end, 0.0)!!
                query.consider(name, hit.point(), hit.part())
            }
            assertEquals("front", query.target())
            assertEquals(Vec3(9.0, 0.0, 0.0), query.point())
            assertEquals(OBB.Part.TURRET, query.part())
        }
    }

    @Test
    fun `nearest OBB is independent of OBB order including rotated boxes and pick inflation`() {
        val near = box(10.0, OBB.Part.WHEEL_LEFT, Math.PI / 4.0)
        val far = box(30.0, OBB.Part.MAIN_ENGINE)
        for (inflation in listOf(0.0, 0.2)) {
            for (boxes in listOf(listOf(far, near), listOf(near, far))) {
                val hit = ProjectileHitSelection.nearestObb(boxes, start, end, inflation)!!
                assertEquals(OBB.Part.WHEEL_LEFT, hit.part())
                assertEquals(10.0 - Math.sqrt(2.0) * (1.0 + inflation), hit.point().x, 1.0e-10)
            }
        }
    }

    @Test
    fun `closed sweep includes endpoint tangent and start inside without accepting behind its end`() {
        val target = box(10.0, OBB.Part.BODY)
        val endpoint = Vec3(9.0, 0.0, 0.0)
        val hit = ProjectileHitSelection.nearestObb(listOf(target), start, endpoint, 0.0)!!
        val query = ProjectileHitSelection.Nearest<String>(start, start.distanceToSqr(endpoint))
        query.consider("endpoint", hit.point(), hit.part())
        assertEquals(endpoint, query.point())
        assertNull(ProjectileHitSelection.nearestObb(listOf(target), start, Vec3(8.99, 0.0, 0.0), 0.0))
        assertNotNull(ProjectileHitSelection.nearestObb(listOf(target), Vec3(0.0, 1.0, 0.0), Vec3(20.0, 1.0, 0.0), 0.0))
        val inside = Vec3(10.0, 0.0, 0.0)
        for (to in listOf(inside, end)) {
            assertEquals(inside, ProjectileHitSelection.nearestObb(listOf(target), inside, to, 0.0)!!.point())
        }
    }

    @Test
    fun `null material miss and nonfinite or out of bounds hits cannot replace selected part`() {
        val query = ProjectileHitSelection.Nearest<String>(start, 100.0)
        query.consider("front", Vec3(5.0, 0.0, 0.0), OBB.Part.BODY)
        query.consider("empty material", null, OBB.Part.TURRET)
        query.consider("invalid", Vec3(Double.NaN, 0.0, 0.0), OBB.Part.WHEEL_RIGHT)
        query.consider("behind", Vec3(11.0, 0.0, 0.0), OBB.Part.MAIN_ENGINE)
        assertEquals("front", query.target())
        assertEquals(OBB.Part.BODY, query.part())
        for (cap in listOf(-1.0, Double.NaN)) {
            val invalid = ProjectileHitSelection.Nearest<String>(start, cap)
            invalid.consider("invalid range", start, OBB.Part.BODY)
            assertNull(invalid.target())
        }
    }

    private data class Hit(val name: String, val point: Vec3, val part: OBB.Part,
                           val result: ProjectileImpactResult)

    @Test
    fun `bullet order uses current sweep even after shooter moves beyond both targets`() {
        val front = Hit("front", Vec3(11.0, 0.0, 0.0), OBB.Part.BODY, ProjectileImpactResult.defaultResult())
        val rear = Hit("rear", Vec3(15.0, 0.0, 0.0), OBB.Part.MAIN_ENGINE, ProjectileImpactResult.defaultResult())
        val currentStart = Vec3(10.0, 0.0, 0.0)
        val movedShooter = Vec3(100.0, 0.0, 0.0)
        assertTrue(movedShooter.distanceToSqr(rear.point) < movedShooter.distanceToSqr(front.point))
        for (input in listOf(listOf(front, rear), listOf(rear, front))) {
            val hits = input.toMutableList()
            ProjectileHitSelection.sortFromStart(hits, currentStart) { it.point }
            assertEquals(listOf(front, rear), hits)
        }
    }

    @Test
    fun `ordered impacts retain consume versus authorized pass through and matching part`() {
        val rear = Hit("rear", Vec3(30.0, 0.0, 0.0), OBB.Part.MAIN_ENGINE,
            ProjectileImpactResult.builder(ProjectileImpactDisposition.BLOCK).build())
        for (disposition in listOf(ProjectileImpactDisposition.BLOCK, ProjectileImpactDisposition.CONSUME,
            ProjectileImpactDisposition.PASS, ProjectileImpactDisposition.PENETRATE)) {
            val front = Hit("front", Vec3(10.0, 0.0, 0.0), OBB.Part.TURRET,
                ProjectileImpactResult.builder(disposition).build())
            val hits = mutableListOf(rear, front)
            ProjectileHitSelection.sortFromStart(hits, start) { it.point }
            val dispatched = mutableListOf<Pair<String, OBB.Part>>()
            for (hit in hits) {
                dispatched += hit.name to hit.part
                if (hit.result.consumesProjectile()) break
            }
            val consumed = disposition == ProjectileImpactDisposition.BLOCK || disposition == ProjectileImpactDisposition.CONSUME
            assertEquals(if (consumed) listOf("front" to OBB.Part.TURRET)
                else listOf("front" to OBB.Part.TURRET, "rear" to OBB.Part.MAIN_ENGINE), dispatched)
        }
    }

    @Test
    fun `block clipped segment excludes both rear vehicle and any stale hit metadata`() {
        val beforeBlock = box(5.0, OBB.Part.WHEEL_RIGHT)
        val behindBlock = box(10.0, OBB.Part.TURRET)
        val blockEndpoint = Vec3(7.0, 0.0, 0.0)
        val hit = ProjectileHitSelection.nearestObb(listOf(behindBlock, beforeBlock), start, blockEndpoint, 0.0)!!
        assertEquals(OBB.Part.WHEEL_RIGHT, hit.part())
        assertEquals(4.0, hit.point().x)
        assertNull(ProjectileHitSelection.nearestObb(listOf(behindBlock), start, blockEndpoint, 0.0))
    }
}
