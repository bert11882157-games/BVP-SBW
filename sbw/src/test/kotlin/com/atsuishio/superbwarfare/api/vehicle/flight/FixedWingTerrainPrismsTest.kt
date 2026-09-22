package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random

class FixedWingTerrainPrismsTest {
    private fun voxel(x: Int, y: Int, z: Int) =
        AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)

    @Test fun rectangularRunwayBecomesOneEquivalentPrism() {
        val terrain = (-10..10).flatMap { x -> (-4..-1).flatMap { y -> (-9..9).map { z -> voxel(x, y, z) } } }
        val merged = FixedWingTerrainPrisms.compact(terrain)
        assertEquals(listOf(AABB(-10.0, -4.0, -9.0, 11.0, 0.0, 10.0)), merged)
        assertEquals(terrain.sumOf { it.xsize * it.ysize * it.zsize }, merged.sumOf { it.xsize * it.ysize * it.zsize })
    }

    @Test fun holesAndSeparatedPlatformsNeverGainSolidVolume() {
        val random = Random(1098337)
        repeat(40) {
            val terrain = (-4..4).flatMap { x -> (-3..0).flatMap { y -> (-4..4).mapNotNull { z ->
                if (random.nextDouble() < 0.65) voxel(x, y, z) else null
            } } }
            val merged = FixedWingTerrainPrisms.compact(terrain)
            assertTrue(merged.size <= terrain.size)
            assertEquals(terrain.size.toDouble(), merged.sumOf { it.xsize * it.ysize * it.zsize })
            for (x in -5..5) for (y in -4..1) for (z in -5..5) {
                val point = Vec3(x + 0.5, y + 0.5, z + 0.5)
                assertEquals(terrain.any { it.contains(point) }, merged.any { it.contains(point) })
            }
        }
    }

    @Test fun partialAndOverhangingCollisionShapesKeepTheirIdentity() {
        val slab = AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0)
        val fence = AABB(1.25, 0.0, 0.25, 1.75, 1.5, 0.75)
        val offset = AABB(2.5, 0.0, 0.0, 3.5, 1.0, 1.0)
        val merged = FixedWingTerrainPrisms.compact(listOf(slab, fence, offset, voxel(-1, -1, 0), voxel(0, -1, 0)))
        assertTrue(merged.any { it === slab })
        assertTrue(merged.any { it === fence })
        assertTrue(merged.any { it === offset })
        assertEquals(4, merged.size)
    }

    @Test fun bankedBodyKeepsOnlyExposedFloorEnergyAfterCompaction() {
        val terrain = (-10..10).flatMap { x -> (-4..-1).flatMap { y -> (-9..9).map { z -> voxel(x, y, z) } } }
        val merged = FixedWingTerrainPrisms.compact(terrain)
        val body = FixedWingContactSweep.Body(OBB(Vector3d(0.0, 0.1, 0.0), Vector3d(4.0, 0.2, 3.0),
            Quaterniond().rotateZ(Math.toRadians(25.0)), OBB.Part.BODY))
        val movement = Vec3(0.0, 0.0, 0.4)
        val raw = requireNotNull(body.sweep(movement, merged.single()))
        val budget = FixedWingContactSurface.Budget()
        val surface = FixedWingContactSurface.resolve(body, movement, merged.single(), raw, merged, budget)
        assertTrue(surface.complete)
        val normal = requireNotNull(surface.normal)
        assertEquals(0.0, normal.x, 1e-12)
        assertEquals(1.0, normal.y, 1e-12)
        assertEquals(0.0, normal.z, 1e-12)
        assertEquals(32768, budget.remaining)
        val damage = FixedWingImpactModel.evaluate(0.0, 64.0, false)
        assertTrue(damage.healthFraction > 0)
        assertFalse(damage.destructive)
    }

    @Test fun exposedWallStillProducesARealHeadOnImpact() {
        val terrain = (0..2).flatMap { x -> (-1..3).flatMap { y -> (-3..3).map { z -> voxel(x, y, z) } } }
        val merged = FixedWingTerrainPrisms.compact(terrain)
        val body = FixedWingContactSweep.Body(OBB(Vector3d(-2.0, 1.0, 0.0), Vector3d(0.5, 0.5, 0.5),
            Quaterniond(), OBB.Part.BODY))
        val movement = Vec3(3.0, 0.0, 0.0)
        val raw = requireNotNull(body.sweep(movement, merged.single()))
        val surface = FixedWingContactSurface.resolve(body, movement, merged.single(), raw, merged,
            FixedWingContactSurface.Budget())
        assertTrue(surface.complete)
        assertEquals(-1.0, requireNotNull(surface.normal).x, 1e-12)
        assertTrue(FixedWingImpactModel.evaluate(64.0, 64.0, false).destructive)
    }
}
