package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random

class FixedWingContactSweepTest {
    @Test fun wheelRotationCompensationRetainsTheDiagonalOutboardWingPath() {
        val rotation = Quaterniond().rotateX(Math.toRadians(-1.0))
        val center = rotation.transform(Vector3d(5.0, 1.0, 0.0))
        val wing = OBB(center, Vector3d(0.25, 0.005, 0.02), rotation, OBB.Part.BODY)
        val movement = Vec3(0.0, 0.05672032092117138, 1.0)
        val obstacle = AABB(4.9, 1.026, 0.478, 5.1, 1.032, 0.504)
        val body = FixedWingContactSweep.Body(wing)
        assertNull(body.sweep(Vec3.ZERO, obstacle))
        assertNull(FixedWingContactSweep.Body(wing.move(movement)).sweep(Vec3.ZERO, obstacle))
        val contact = requireNotNull(body.sweep(movement, obstacle))
        assertFalse(contact.initiallyOverlapping)
        assertTrue(contact.fraction in 0.4..0.6)
        assertTrue(FixedWingContactSurface.resolve(body, movement, obstacle, contact,
            listOf(obstacle), FixedWingContactSurface.Budget()).complete)
        // Raising first and sweeping horizontally would miss the actual intermediate collision.
        assertNull(FixedWingContactSweep.Body(wing.move(Vec3(0.0, movement.y, 0.0)))
            .sweep(Vec3(0.0, 0.0, movement.z), obstacle))
    }

    @Test fun partitionedFighterKeepsItsExposedFloorContactWithoutEmbeddedFrontalHits() {
        // Authored fighter OBBs 19 and 26..28, replayed at the observed bank angles.
        val extents = listOf(Vector3d(1.0175, 0.1175, 1.8725),
            Vector3d(0.38886, 0.09895, 0.0948), Vector3d(0.31029, 0.12898, 0.06508),
            Vector3d(0.25773, 0.14907, 0.04517))
        val centers = listOf(
            listOf(Vector3d(2045.6625545629413, -60.25470664490372, 2046.6775),
                Vector3d(2046.7610356275627, -60.427585929574725, 2046.17598),
                Vector3d(2046.7628486599056, -60.431473989981114, 2046.32585),
                Vector3d(2046.7640615743167, -60.43407509332991, 2046.42608)),
            listOf(Vector3d(2045.662458591208, -60.25458098680935, 2046.6775),
                Vector3d(2046.7609319988117, -60.42750891742789, 2046.17598),
                Vector3d(2046.7627448589703, -60.43139705812042, 2046.32585),
                Vector3d(2046.7639576581907, -60.433998215180445, 2046.42608)))
        val terrain = (2043..2049).flatMap { x -> (2043..2050).map { z ->
            AABB(x.toDouble(), -61.0, z.toDouble(), x + 1.0, -60.0, z + 1.0) } }
        val movement = Vec3(-0.000019240529, 0.0, 0.399953730918)
        for ((frame, roll) in listOf(25.0, 24.9974626625).withIndex()) {
            val budget = FixedWingContactSurface.Budget()
            var floorContact = false
            for (index in centers[frame].indices) {
                val body = FixedWingContactSweep.Body(OBB(centers[frame][index], extents[index],
                    Quaterniond().rotateZ(Math.toRadians(roll)), OBB.Part.BODY))
                for (obstacle in terrain) {
                    val raw = body.sweep(movement, obstacle) ?: continue
                    val result = FixedWingContactSurface.resolve(body, movement, obstacle, raw, terrain, budget)
                    assertTrue(result.complete)
                    result.normal?.let {
                        assertEquals(0, index, "embedded partitions must not invent an exposed normal")
                        assertEquals(Vec3(0.0, 1.0, 0.0), it)
                        assertEquals(0.0, movement.dot(it), 1e-12)
                        floorContact = true
                    }
                }
            }
            assertTrue(floorContact, "the genuine wing/floor overlap must remain a scrape")
        }
    }

    @Test fun embeddedPartitionDoesNotCertifyItsSidewaysSatNormalAsAFrontalImpact() {
        val body = FixedWingContactSweep.Body(box(0.5, -0.5, 0.1, Vector3d(0.05, 0.05, 0.02)))
        val floor = AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0)
        val movement = Vec3(0.0, 0.0, 0.4)
        val raw = requireNotNull(body.sweep(movement, floor))
        assertTrue(raw.initiallyOverlapping)
        assertEquals(0.0, raw.normal.distanceTo(Vec3(0.0, 0.0, -1.0)), 1e-12)
        val result = FixedWingContactSurface.resolve(body, movement, floor, raw,
            listOf(floor), FixedWingContactSurface.Budget())
        assertTrue(result.complete)
        assertNull(result.normal, "overlap alone cannot certify a surface normal")
        val scrape = FixedWingImpactModel.evaluate(0.0, movement.lengthSqr() * 400, false)
        assertTrue(scrape.healthFraction > 0.0)
        assertFalse(scrape.destructive)
    }

    @Test fun initialOverlapFindsExposedFloorEvenWhenSatSelectsAnInternalSide() {
        val body = FixedWingContactSweep.Body(box(0.5, -0.02, 0.1, Vector3d(0.2, 0.25, 0.02)))
        val floor = AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0)
        val neighbor = AABB(0.0, -1.0, -1.0, 1.0, 0.0, 0.0)
        val movement = Vec3(0.0, 0.0, 0.4)
        val raw = requireNotNull(body.sweep(movement, floor))
        assertEquals(0.0, raw.normal.distanceTo(Vec3(0.0, 0.0, -1.0)), 1e-12)
        val result = FixedWingContactSurface.resolve(body, movement, floor, raw,
            listOf(floor, neighbor), FixedWingContactSurface.Budget())
        assertTrue(result.complete)
        assertEquals(Vec3(0.0, 1.0, 0.0), result.normal)
    }

    @Test fun exposedWallAndDiveStillCarryLethalNormalEnergy() {
        val body = FixedWingContactSweep.Body(box(y = 2.0))
        for ((movement, obstacle) in listOf(
            Vec3(5.0, 0.0, 0.0) to AABB(2.0, 0.0, -1.0, 2.01, 4.0, 1.0),
            Vec3(0.0, -3.0, 0.0) to AABB(-2.0, -1.0, -2.0, 2.0, 0.0, 2.0))) {
            val raw = requireNotNull(body.sweep(movement, obstacle))
            val result = FixedWingContactSurface.resolve(body, movement, obstacle, raw,
                listOf(obstacle), FixedWingContactSurface.Budget())
            assertTrue(result.complete)
            val inward = -movement.dot(requireNotNull(result.normal)) * 20.0
            assertTrue(FixedWingImpactModel.evaluate(inward * inward,
                movement.lengthSqr() * 400.0, false).destructive)
        }
    }

    private fun visible(body: FixedWingContactSweep.Body, movement: Vec3, obstacle: AABB,
                        terrain: List<AABB>): Boolean? {
        val result = FixedWingContactSurface.resolve(body, movement, obstacle,
            requireNotNull(body.sweep(movement, obstacle)), terrain, FixedWingContactSurface.Budget())
        return if (result.complete) result.normal != null else null
    }

    @Test fun flatFloorSeamsDoNotBecomeWallsDuringShallowScraping() {
        val movement = Vec3(0.0, 0.0, 0.4)
        for (roll in listOf(0.0, 0.1, -0.1, Math.PI)) {
            val body = FixedWingContactSweep.Body(box(0.5, 0.05, 0.4,
                Vector3d(0.4, 0.1, 0.5), Quaterniond().rotateZ(roll)))
            val floor = listOf(AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0),
                AABB(0.0, -1.0, 1.0, 1.0, 0.0, 2.0))
            assertEquals(true, visible(body, movement, floor[0], floor))
            assertEquals(false, visible(body, movement, floor[1], floor))
        }
    }

    @Test fun contactPatchDistinguishesCoveredBaseFromExposedStep() {
        val movement = Vec3(0.0, 0.0, 0.4)
        val terrain = listOf(AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0),
            AABB(0.0, -1.0, 1.0, 1.0, 0.5, 2.0))
        val below = FixedWingContactSweep.Body(box(0.5, -0.1, 0.4, Vector3d(0.4, 0.05, 0.5)))
        val above = FixedWingContactSweep.Body(box(0.5, 0.2, 0.4, Vector3d(0.4, 0.1, 0.5)))
        assertEquals(false, visible(below, movement, terrain[1], terrain))
        assertEquals(true, visible(above, movement, terrain[1], terrain))
    }

    @Test fun multipleNeighborShapesCanCoverOneFaceButAnActualGapRemainsExposed() {
        val movement = Vec3(0.0, 0.0, 0.4)
        val body = FixedWingContactSweep.Body(box(0.5, -0.1, 0.4, Vector3d(0.4, 0.05, 0.5)))
        val target = AABB(0.0, -1.0, 1.0, 1.0, 0.0, 2.0)
        val left = AABB(0.0, -1.0, 0.0, 0.5, 0.0, 1.0)
        val right = AABB(0.5, -1.0, 0.0, 1.0, 0.0, 1.0)
        assertEquals(false, visible(body, movement, target, listOf(target, left, right)))
        assertEquals(true, visible(body, movement, target, listOf(target, left)))
    }

    @Test fun actualThinWallAndDescendingFloorImpactRemainExposed() {
        val body = FixedWingContactSweep.Body(box(y = 2.0))
        val wall = AABB(2.0, 0.0, -1.0, 2.01, 4.0, 1.0)
        assertEquals(true, visible(body, Vec3(5.0, 0.0, 0.0), wall, listOf(wall)))
        val floor = AABB(-2.0, -1.0, -2.0, 2.0, 0.0, 2.0)
        assertEquals(true, visible(body, Vec3(0.0, -3.0, 0.0), floor, listOf(floor)))
    }

    @Test fun unavailableSurfaceBudgetReturnsUnknownRatherThanClear() {
        val body = FixedWingContactSweep.Body(box(0.5, 0.05, 0.4, Vector3d(0.4, 0.1, 0.5)))
        val movement = Vec3(0.0, 0.0, 0.4)
        val target = AABB(0.0, -1.0, 1.0, 1.0, 0.0, 2.0)
        val neighbor = AABB(0.0, -1.0, 0.0, 1.0, 0.0, 1.0)
        val result = FixedWingContactSurface.resolve(body, movement, target,
            requireNotNull(body.sweep(movement, target)), listOf(target, neighbor),
            FixedWingContactSurface.Budget(0))
        assertFalse(result.complete)
        assertNull(result.normal)
    }

    @Test fun bankedScrapesAcrossVoxelCornersUseTheExposedFloorNormal() {
        val random = Random(13519L)
        val terrain = (-3..3).flatMap { x -> (-3..3).map { z ->
            AABB(x.toDouble(), -1.0, z.toDouble(), x + 1.0, 0.0, z + 1.0) } }
        repeat(100) {
            val body = FixedWingContactSweep.Body(box(0.2, 0.15, 0.3,
                Vector3d(1.2, 0.1, 0.4), Quaterniond().rotateXYZ(
                    random.nextDouble() * 0.3, random.nextDouble() * 6.28, random.nextDouble() * 0.5)))
            val movement = Vec3(random.nextDouble() * 0.8 - 0.4, 0.0, random.nextDouble() * 0.8 - 0.4)
            val budget = FixedWingContactSurface.Budget()
            for (obstacle in terrain) {
                val contact = body.sweep(movement, obstacle) ?: continue
                val result = FixedWingContactSurface.resolve(body, movement, obstacle, contact, terrain, budget)
                assertTrue(result.complete)
                result.normal?.let { assertEquals(Vec3(0.0, 1.0, 0.0), it) }
            }
        }
    }

    @Test fun internalSeamRejectionIsIndependentOfWorldAxisAndCoordinateSign() {
        for (axis in 0..2) for (sign in listOf(-1.0, 1.0)) {
            fun transform(x: Double, y: Double, z: Double): Vec3 {
                val values = doubleArrayOf(x, y, z)
                val result = DoubleArray(3)
                for (index in 0..2) result[(index + axis) % 3] = values[index] * sign
                return Vec3(result[0] + 8000.0, result[1] + 30.0, result[2] - 6000.0)
            }
            fun terrainBox(z: Double): AABB = AABB(transform(0.0, -1.0, z), transform(1.0, 0.0, z + 1.0))
            val center = transform(0.5, 0.05, 0.4)
            val half = doubleArrayOf(0.4, 0.1, 0.5)
            val extents = Vector3d(half[(3 - axis) % 3], half[(4 - axis) % 3], half[(5 - axis) % 3])
            val body = FixedWingContactSweep.Body(box(center.x, center.y, center.z, extents))
            val movement = transform(0.0, 0.0, 0.4).subtract(transform(0.0, 0.0, 0.0))
            val terrain = listOf(terrainBox(0.0), terrainBox(1.0))
            assertEquals(true, visible(body, movement, terrain[0], terrain))
            assertEquals(false, visible(body, movement, terrain[1], terrain))
        }
    }
    private fun box(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0,
                    half: Vector3d = Vector3d(0.5), rotation: Quaterniond = Quaterniond()) =
        OBB(Vector3d(x, y, z), half, rotation, OBB.Part.BODY)

    @Test fun fastThinWallImpactIsDetectedBetweenNonOverlappingEndpoints() {
        val shape = box()
        val wall = AABB(2.0, -1.0, -1.0, 2.01, 1.0, 1.0)
        assertFalse(OBB.isColliding(shape, wall))
        assertFalse(OBB.isColliding(shape.move(Vec3(5.0, 0.0, 0.0)), wall))
        val hit = requireNotNull(FixedWingContactSweep.Body(shape).sweep(Vec3(5.0, 0.0, 0.0), wall))
        assertEquals(0.3, hit.fraction, 1e-12)
        assertEquals(-1.0, hit.normal.x, 1e-12)
        assertEquals(0.0, hit.normal.y, 1e-12)
        assertEquals(0.0, hit.normal.z, 1e-12)
        assertFalse(hit.initiallyOverlapping)
    }

    @Test fun realWingShapeRejectsAnEmptyBroadphaseCorner() {
        val shape = box(half = Vector3d(4.0, 0.05, 0.15), rotation = Quaterniond().rotateY(Math.PI / 4))
        val corner = AABB(2.0, -0.1, 2.0, 2.2, 0.1, 2.2)
        val body = FixedWingContactSweep.Body(shape)
        assertTrue(body.bounds.intersects(corner))
        assertNull(body.sweep(Vec3.ZERO, corner))
    }

    @Test fun resolvedPathRebaseCannotHitTerrainBeyondABlockingContact() {
        val start = box(x = 8000.0, y = 30.0, z = -6000.0)
        val resolved = Vec3(1.0, 0.0, 0.0)
        val current = start.move(resolved)
        val rebased = FixedWingContactSweep.Body(current.move(resolved.scale(-1.0)))
        val distant = AABB(8002.0, 29.0, -6001.0, 8002.1, 31.0, -5999.0)
        assertNotNull(rebased.sweep(Vec3(5.0, 0.0, 0.0), distant))
        assertNull(rebased.sweep(resolved, distant))
        val reached = AABB(8001.5, 29.0, -6001.0, 8001.6, 31.0, -5999.0)
        val hit = requireNotNull(rebased.sweep(resolved, reached))
        assertEquals(1.0, hit.fraction, 1e-9)
        assertEquals(-1.0, hit.normal.x, 1e-9)
        assertEquals(8001.0, current.center.x, 1e-9)
    }

    @Test fun floorNormalsAndTouchingReleaseRemainCorrectWhenInverted() {
        for (roll in listOf(0.0, Math.PI)) {
            val body = FixedWingContactSweep.Body(box(y = 2.0, rotation = Quaterniond().rotateZ(roll)))
            val floor = AABB(-10.0, -1.0, -10.0, 10.0, 0.0, 10.0)
            val hit = requireNotNull(body.sweep(Vec3(0.0, -3.0, 0.0), floor))
            assertEquals(0.5, hit.fraction, 1e-12)
            assertEquals(1.0, hit.normal.y, 1e-12)
            val touching = FixedWingContactSweep.Body(box(y = 0.5, rotation = Quaterniond().rotateZ(roll)))
            assertNull(touching.sweep(Vec3(0.0, 0.1, 0.0), floor))
            assertNotNull(touching.sweep(Vec3(0.1, 0.0, 0.0), floor))
        }
    }

    @Test fun translatedRotatedSweepsAgreeWithActualObbIntersection() {
        val random = Random(681932L)
        repeat(500) {
            val shape = box(random.nextDouble() * 10000, random.nextDouble() * 1000,
                random.nextDouble() * -10000, Vector3d(0.2 + random.nextDouble() * 3,
                    0.1 + random.nextDouble(), 0.2 + random.nextDouble() * 4),
                Quaterniond().rotateXYZ(random.nextDouble() * 6, random.nextDouble() * 6, random.nextDouble() * 6))
            val travel = Vec3(random.nextDouble() * 8 - 4, random.nextDouble() * 8 - 4, random.nextDouble() * 8 - 4)
            val midpoint = Vec3(shape.center.x, shape.center.y, shape.center.z).add(travel.scale(0.5))
            val obstacle = newBox(midpoint, 0.1)
            val hit = requireNotNull(FixedWingContactSweep.Body(shape).sweep(travel, obstacle))
            assertTrue(hit.fraction in 0.0..0.5)
            assertEquals(1.0, hit.normal.length(), 1e-9)
            assertTrue(OBB.isColliding(shape.move(travel.scale((hit.fraction + 1e-7).coerceAtMost(0.5))), obstacle))
        }
    }

    private fun newBox(point: Vec3, half: Double) =
        AABB(point.x - half, point.y - half, point.z - half, point.x + half, point.y + half, point.z + half)
}
