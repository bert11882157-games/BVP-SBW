package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainPolicyTest {
    @Test fun `tiny lateral motion cannot replace an admitted equal-distance corridor`() {
        for (observerX in listOf(-0.0001, 0.0, 0.0001)) for (retainedSide in listOf(-1.0, 1.0)) {
            val retainedX = retainedSide * 64.0 - observerX
            val incomingX = -retainedSide * 64.0 - observerX
            val retained = FarTerrainPolicy.selectionDistance(retainedX * retainedX + 528.0 * 528.0, true)
            val incoming = FarTerrainPolicy.selectionDistance(incomingX * incomingX + 528.0 * 528.0, false)
            assertTrue(retained < incoming, "a tiny reversal would discard acknowledged terrain")
        }
    }

    @Test fun `retention preference yields to nearer arrivals and never extends radius`() {
        assertTrue(FarTerrainPolicy.selectionDistance(480.0 * 480.0, false) <
            FarTerrainPolicy.selectionDistance(528.0 * 528.0, true))
        assertTrue(FarTerrainPolicy.selectionDistance(0.0, true) >= 0.0)
        assertFalse(FarTerrainPolicy.inside(0.0, 0.0, 0.0, 576.01, 576))
    }

    @Test fun `native tracked vehicles cannot bypass distant terrain readiness`() {
        assertFalse(FarTerrainPolicy.deferNative(100.0, 8, 2.0, true, false))
        assertTrue(FarTerrainPolicy.deferNative(200.0 * 200.0, 8, 2.0, true, false))
        assertFalse(FarTerrainPolicy.deferNative(200.0 * 200.0, 8, 2.0, false, false))
        assertFalse(FarTerrainPolicy.deferNative(200.0 * 200.0, 8, 2.0, true, true))
    }
    @Test fun `acquisition radius is exactly five effective chunk distances`() {
        assertEquals(640, FarTerrainPolicy.radius(8, 16))
        assertEquals(800, FarTerrainPolicy.radius(32, 10))
        assertEquals(2560, FarTerrainPolicy.radius(128, 128))
        assertEquals(160, FarTerrainPolicy.radius(-1, 10))
        assertTrue(FarTerrainPolicy.inside(0.0, 0.0, 384.0, 0.0, 384))
        assertFalse(FarTerrainPolicy.inside(0.0, 0.0, 384.01, 0.0, 384))
        assertFalse(FarTerrainPolicy.inside(0.0, 0.0, 384.0, 384.0, 384))
        assertFalse(FarTerrainPolicy.inside(0.0, 0.0, Double.NaN, 0.0, 384))
    }

    @Test fun `corridor includes intervening cover and negative coordinate boundaries`() {
        val path = FarTerrainPolicy.corridor(-0.1, -0.1, -320.1, -160.1)
        for (i in 0..320) {
            val x = -0.1 - i
            val z = -0.1 - i / 2.0
            assertTrue(FarTerrainPolicy.key(FarTerrainPolicy.chunk(x), FarTerrainPolicy.chunk(z)) in path)
        }
        assertTrue(FarTerrainPolicy.key(-22, -12) in path)
        assertEquals(-1, FarTerrainPolicy.chunk(-0.1))
        for (x in listOf(-1875000, -1, 0, 1875000)) for (z in listOf(-1875000, -1, 0, 1875000)) {
            val key = FarTerrainPolicy.key(x, z)
            assertEquals(x, FarTerrainPolicy.x(key)); assertEquals(z, FarTerrainPolicy.z(key))
        }
    }

    @Test fun `quota is atomic and shared corridors are deduplicated`() {
        val first = (0L until 500L).toSet()
        assertEquals(512, FarTerrainPolicy.boundedUnion(first, (490L until 512L).toSet(), 512)!!.size)
        assertNull(FarTerrainPolicy.boundedUnion(first, (500L until 513L).toSet(), 512))
        assertEquals(500, first.size)
    }

    @Test fun `world spanning retained sightlines cannot allocate unbounded corridors`() {
        val corridor = FarTerrainPolicy.renderCoverage(-29_000_000.0, -29_000_000.0,
            29_000_000.0, 29_000_000.0, 29_000_010.0, 29_000_010.0)
        assertEquals(FarTerrainPolicy.MAX_CHUNKS + 1, corridor.size)
        assertNull(FarTerrainPolicy.boundedUnion(emptySet(), corridor))
    }

    @Test fun `prefetch boundary changes do not expand the terrain needed to obscure a vehicle`() {
        val before = FarTerrainPolicy.coverage(15.5, 8.5, 157.5, 156.5, 163.5, 164.5)
        val after = FarTerrainPolicy.coverage(24.5, 8.5, 157.5, 156.5, 163.5, 164.5)
        val required = FarTerrainPolicy.renderCoverage(24.5, 8.5, 157.5, 156.5, 163.5, 164.5)
        assertNotEquals(before, after)
        assertTrue(before.containsAll(required), "Movement uses terrain already prefetched")
        assertTrue(after.containsAll(required))
        assertTrue(required.size < after.size)
    }

    @Test fun `required footprint contains interior cover and all grid corner contacts`() {
        val diagonal = FarTerrainPolicy.requiredCoverage(0.5, 0.5, 32.5, 32.5, 32.5, 32.5)
        for (cell in listOf(0 to 1, 1 to 0, 1 to 1, 1 to 2, 2 to 1, 2 to 2)) {
            assertTrue(FarTerrainPolicy.key(cell.first, cell.second) in diagonal)
        }
        val interior = FarTerrainPolicy.requiredCoverage(8.5, 8.5, 96.5, 160.5, 224.5, 192.5)
        assertTrue(FarTerrainPolicy.key(10, 10) in interior, "Cover between corner rays is required")
        assertFalse(FarTerrainPolicy.key(-5, 5) in interior, "Unrelated terrain is not required")
    }

    @Test fun `protruding model parts require cover beyond the collision box`() {
        val collisionOnly = FarTerrainPolicy.requiredCoverage(8.5, 8.5, 12.0, 319.0, 15.0, 322.0)
        val rendered = FarTerrainPolicy.renderCoverage(8.5, 8.5, 12.0, 319.0, 15.0, 322.0)
        val outerCover = FarTerrainPolicy.key(1, 20)
        assertFalse(outerCover in collisionOnly)
        assertTrue(outerCover in rendered, "Cover near a protruding barrel or wing is mandatory")
        val gate = FarTerrainHandshake("s", "d")
        gate.update(576, FarTerrainPolicy.coverage(8.5, 8.5, 12.0, 319.0, 15.0, 322.0), emptySet())
        for (chunk in rendered - outerCover) gate.delivered(gate.revision, chunk)
        gate.acknowledge("s", "d", gate.revision, rendered - outerCover)
        assertFalse(gate.readyFor(rendered), "Core terrain alone must not reveal the whole model")
        gate.delivered(gate.revision, outerCover)
        gate.acknowledge("s", "d", gate.revision, rendered)
        assertTrue(gate.readyFor(rendered))
    }

    @Test fun `footprint covers sampled sightlines across large bounds and negative coordinates`() {
        for ((px, pz) in listOf(-97.1 to -63.5, 8.5 to 8.5, 63.9 to -48.0, 200.0 to 200.0)) {
            val minX = -16.5; val maxX = 81.5
            val minZ = 48.0; val maxZ = 112.0
            val required = FarTerrainPolicy.requiredCoverage(px, pz, minX, minZ, maxX, maxZ)
            for (ix in 0..8) for (iz in 0..8) for (step in 0..256) {
                val targetX = minX + (maxX - minX) * ix / 8
                val targetZ = minZ + (maxZ - minZ) * iz / 8
                val x = px + (targetX - px) * step / 256
                val z = pz + (targetZ - pz) * step / 256
                assertTrue(FarTerrainPolicy.key(FarTerrainPolicy.chunk(x), FarTerrainPolicy.chunk(z)) in required,
                    "Sightline terrain missing at $x, $z")
            }
        }
    }

    @Test fun `bounded forward prefetch covers intermediate observer positions`() {
        val path = linkedSetOf<Long>()
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            path.addAll(FarTerrainPolicy.coverage(8.5 + 60.0 * fraction, 8.5 + 20.0 * fraction,
                350.5, 310.5, 360.5, 320.5))
        }
        for (step in 0..20) {
            val required = FarTerrainPolicy.renderCoverage(8.5 + 3.0 * step, 8.5 + step,
                350.5, 310.5, 360.5, 320.5)
            assertTrue(path.containsAll(required), "Prefetched path missing terrain on tick $step")
        }
        assertTrue(path.size <= FarTerrainPolicy.MAX_CHUNKS)
    }
}
