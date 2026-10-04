package com.atsuishio.superbwarfare.api.vehicle.deck

import net.minecraft.core.Direction
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/**
 * Owner 2026-10-03: a carrier's top deck must be "something players are readily able to walk on no matter how the
 * ship has turned ... it needs to perform exactly like how terrain does" (landing, braking, placing vehicles).
 */
class DeckSurfaceTest {
    private val cell = 0.25
    /** 20 x 60 block hull: flight deck 14.0, a 4 x 12 island to 30.0, a lowered gallery, keel -9. */
    private val surface: DeckSurface = run {
        val nx = 80; val nz = 240
        val top = FloatArray(nx * nz) { Float.NaN }
        val bottom = FloatArray(nx * nz) { Float.NaN }
        for (j in 0 until nz) for (i in 0 until nx) {
            val x = -10.0 + (i + 0.5) * cell
            val z = -30.0 + (j + 0.5) * cell
            val k = j * nx + i
            // tapered bow: no columns outside |x| < 10 - (z - 20) for z > 20
            if (z > 20.0 && abs(x) > 10.0 - (z - 20.0)) continue
            top[k] = when {
                x < -5.0 && x > -9.0 && z > -6.0 && z < 6.0 -> 30f
                x > 8.0 && z < -20.0 -> 9f
                else -> 14f
            }
            bottom[k] = if (x > 8.0 && z < -20.0) 5f else -9f
        }
        DeckSurface(cell, -10.0, -30.0, nx, nz, 14.0, top, bottom)
    }

    @Test
    fun rleRoundTrip() {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(224).putShort(3).putShort(DeckSurface.EMPTY).putShort(2)
        val values = DeckSurface.decode(Base64.getEncoder().encodeToString(buffer.array().copyOf(8)), 5)
        assertEquals(14f, values[0]); assertEquals(14f, values[2])
        assertTrue(values[3].isNaN() && values[4].isNaN())
    }

    @Test
    fun poseMatchesTheRenderer() {
        // yaw 0 faces +Z (south), its left is +X (east); yaw 90 faces -X (west), its left is +Z
        val south = DeckPose(0.0, 0.0, 0.0, 0f)
        assertEquals(1.0, south.worldZ(0.0, 1.0), 1e-12)
        assertEquals(1.0, south.worldX(1.0, 0.0), 1e-12)
        val west = DeckPose(0.0, 0.0, 0.0, 90f)
        assertEquals(-1.0, west.worldX(0.0, 1.0), 1e-12)
        assertEquals(1.0, west.worldZ(1.0, 0.0), 1e-12)
        val pose = DeckPose(12.5, 63.0, -40.25, 37.3f)
        val wx = pose.worldX(3.0, -7.0); val wz = pose.worldZ(3.0, -7.0)
        assertEquals(3.0, pose.localX(wx, wz), 1e-9)
        assertEquals(-7.0, pose.localZ(wx, wz), 1e-9)
    }

    /** The merged boxes are exactly the sampled columns: no gap, no overlap, at any yaw. */
    @Test
    fun boxesAreTheColumnsAtAnyYaw() {
        val random = Random(7)
        repeat(40) {
            val pose = DeckPose(random.nextDouble(-500.0, 500.0), 62.89, random.nextDouble(-500.0, 500.0),
                random.nextFloat() * 360f - 180f)
            val lx = random.nextDouble(-12.0, 12.0); val lz = random.nextDouble(-32.0, 32.0)
            val cx = pose.worldX(lx, lz); val cz = pose.worldZ(lx, lz)
            val q = AABB(cx - 3.3, pose.y + 10.0, cz - 2.7, cx + 3.1, pose.y + 16.0, cz + 4.2)
            val boxes = ArrayList<AABB>()
            DeckCollisions.rasterize(surface, pose, q, boxes)
            for (a in boxes.indices) for (b in a + 1 until boxes.size) {
                assertTrue(!boxes[a].intersects(boxes[b]), "boxes overlap: ${boxes[a]} ${boxes[b]}")
            }
            var x = floor(q.minX / 0.25) * 0.25 + 0.125
            while (x < q.maxX) {
                var z = floor(q.minZ / 0.25) * 0.25 + 0.125
                while (z < q.maxZ) {
                    val k = surface.column(pose.localX(x, z), pose.localZ(x, z))
                    val covering = boxes.filter { x > it.minX && x < it.maxX && z > it.minZ && z < it.maxZ }
                    if (k < 0) {
                        assertTrue(covering.isEmpty(), "box over open water at $x $z")
                    } else {
                        val top = pose.y + surface.topOf(k)
                        val bottom = pose.y + surface.bottomOf(k)
                        if (top >= q.minY && bottom <= q.maxY) {
                            assertEquals(1, covering.size, "column at $x $z not covered once")
                            assertEquals(top, covering[0].maxY, 1e-9)
                            assertEquals(bottom, covering[0].minY, 1e-9)
                        }
                    }
                    z += 0.25
                }
                x += 0.25
            }
        }
    }

    /** A query on the open flight deck is one box, and a falling body stops exactly on the deck plane. */
    @Test
    fun flightDeckIsOneFloor() {
        val pose = DeckPose(100.0, 62.888, -50.0, 23f)
        val cx = pose.worldX(4.0, 0.0); val cz = pose.worldZ(4.0, 0.0)
        val body = AABB(cx - 0.3, pose.y + 14.5, cz - 0.3, cx + 0.3, pose.y + 16.3, cz + 0.3)
        val boxes = ArrayList<AABB>()
        DeckCollisions.rasterize(surface, pose, body.expandTowards(0.0, -1.0, 0.0), boxes)
        assertEquals(1, boxes.size)
        val shapes = boxes.map { Shapes.create(it) }
        val allowed = Shapes.collide(Direction.Axis.Y, body, shapes, -1.0)
        assertEquals(-0.5, allowed, 1e-7)
        // walking along the deck is not obstructed
        val standing = body.move(0.0, allowed, 0.0)
        val along = ArrayList<AABB>()
        DeckCollisions.rasterize(surface, pose, standing.expandTowards(0.4, -0.08, 0.4), along)
        val shapesAlong = along.map { Shapes.create(it) }
        assertEquals(0.4, Shapes.collide(Direction.Axis.X, standing, shapesAlong, 0.4), 1e-9)
        assertEquals(0.4, Shapes.collide(Direction.Axis.Z, standing, shapesAlong, 0.4), 1e-9)
    }

    @Test
    fun islandIsAWall() {
        val pose = DeckPose(0.0, 0.0, 0.0, 0f)
        // standing on the deck at x = -4, walking toward -X into the island (x -5..-9)
        val body = AABB(-4.6, 14.0, -0.3, -4.0, 15.8, 0.3)
        val boxes = ArrayList<AABB>()
        DeckCollisions.rasterize(surface, pose, body.expandTowards(-1.0, 0.0, 0.0), boxes)
        val allowed = Shapes.collide(Direction.Axis.X, body, boxes.map { Shapes.create(it) }, -1.0)
        assertEquals(-0.4, allowed, 1e-9)
    }

    @Test
    fun clipHitsTheDeckFromAbove() {
        val pose = DeckPose(10.0, 62.888, 20.0, -60f)
        val px = pose.worldX(2.0, 5.0); val pz = pose.worldZ(2.0, 5.0)
        val hit = DeckCollisions.clip(surface, pose, Vec3(px + 1.0, pose.y + 18.0, pz), Vec3(px, pose.y + 10.0, pz))
        assertNotNull(hit)
        assertEquals(pose.y + 14.0, hit!!.first.y, 1e-9)
        assertTrue(hit.second)
        assertNull(DeckCollisions.clip(surface, pose, Vec3(px + 40.0, pose.y + 18.0, pz),
            Vec3(px + 40.0, pose.y + 2.0, pz)))
        // a level ray into the island side enters through the wall, not a top
        val side = DeckCollisions.clip(surface, DeckPose(0.0, 0.0, 0.0, 0f), Vec3(0.0, 20.0, 0.0), Vec3(-8.0, 20.0, 0.0))
        assertNotNull(side)
        assertEquals(-5.0, side!!.first.x, 1e-3)
        assertTrue(!side.second)
    }

    @Test
    fun surfaceBelowFindsTheColumnTop() {
        val pose = DeckPose(0.0, 60.0, 0.0, 0f)
        val k = surface.column(9.0, -25.0)
        assertTrue(k >= 0)
        assertEquals(9.0, surface.topOf(k), 1e-9)       // the lowered gallery
        assertEquals(14.0, surface.topAt(0.0, 0.0), 1e-9)
        assertTrue(surface.topAt(9.9, 29.0).isNaN())     // outside the tapered bow
        assertEquals(Math.sqrt(1000.0), surface.radius, 1e-9)
        val env = pose.envelope(surface)
        assertEquals(51.0, env.minY, 1e-6)
        assertEquals(90.0, env.maxY, 1e-6)
    }
}

