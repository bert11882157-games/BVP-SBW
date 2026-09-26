package com.atsuishio.superbwarfare.client.renderer.vehicle

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class TrackBeltPathTest {
    private fun belt(z: FloatArray, y: FloatArray, r: FloatArray, ordered: Boolean = false): TrackBeltPath =
        TrackBeltPath.fromWheels(z, y, r, ordered).also { assertNotNull(it) }!!

    @Test fun `two equal wheels make a stadium`() {
        val path = belt(floatArrayOf(0F, 100F), floatArrayOf(0F, 0F), floatArrayOf(10F, 10F))
        assertEquals(200F + 2F * PI.toFloat() * 10F, path.length, 0.2F)
        val p = FloatArray(2)
        path.pointInto(0F, p, 0)
        assertEquals(0F, p[0], 1e-3F); assertEquals(10F, p[1], 1e-3F)   // starts on top of the front wheel
        path.pointInto(50F, p, 0)
        assertEquals(50F, p[0], 1e-3F); assertEquals(10F, p[1], 1e-3F)  // runs toward +z along the top
        path.pointInto(100F + PI.toFloat() * 10F + 50F, p, 0)
        assertEquals(50F, p[0], 0.05F); assertEquals(-10F, p[1], 0.05F) // back along the bottom
    }

    @Test fun `belt stays outside every wheel and touches the hull wheels`() {
        // road wheels, raised idler at the front, sprocket at the rear, a return roller under the top run
        val z = floatArrayOf(-50F, -34F, -18F, -5F, 8F, 22F, 35F, 49F, 63F, 10F)
        val y = floatArrayOf(17F, 7.8F, 7.8F, 7.8F, 7.8F, 7.8F, 7.8F, 7.8F, 18.5F, 14F)
        val r = floatArrayOf(7F, 7.5F, 7.5F, 7.5F, 7.5F, 7.5F, 7.5F, 7.5F, 7.5F, 2F)
        val path = belt(z, y, r)
        val p = FloatArray(2)
        var s = 0F
        while (s < path.length) {
            path.pointInto(s, p, 0)
            for (i in z.indices) assertTrue(hypot(p[0] - z[i], p[1] - y[i]) >= r[i] - 0.05F, "inside wheel $i at $s")
            s += 0.5F
        }
        assertTrue(9 !in path.touched.toList(), "the low return roller is under the straight top run")
        assertEquals(0, path.touched.first(), "starts at the front-most wheel")
        // the bottom run is tangent to every road wheel
        for (i in 1..7) {
            var best = Float.MAX_VALUE
            s = 0F
            while (s < path.length) { path.pointInto(s, p, 0); best = minOf(best, hypot(p[0] - z[i], p[1] - y[i]) - r[i]); s += 0.25F }
            assertTrue(best < 0.1F, "road wheel $i not touched ($best)")
        }
    }

    @Test fun `ordered belts may sag onto rollers`() {
        val z = floatArrayOf(0F, 50F, 100F, 100F, 0F)
        val y = floatArrayOf(20F, 12F, 20F, 0F, 0F)
        val r = floatArrayOf(5F, 5F, 5F, 5F, 5F)
        val hull = belt(z, y, r)
        val sag = belt(z, y, r, ordered = true)
        assertTrue(1 !in hull.touched.toList())
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), sag.touched)
        assertTrue(sag.length > hull.length - 1F)
    }

    @Test fun `links tile the belt and follow the travel`() {
        val path = belt(floatArrayOf(0F, 100F), floatArrayOf(0F, 0F), floatArrayOf(10F, 10F))
        val count = TrackBeltLinks.countFor(path.length, 4F, 200)
        assertEquals(Math.round(path.length / 4F), count)
        assertEquals(40, TrackBeltLinks.countFor(path.length, 4F, 40))
        val links = TrackBeltLinks(path, count, 4F, 1.5F)
        val a = FloatArray(4); val b = FloatArray(4)
        links.linkInto(3, 0F, a, 0)
        links.linkInto(2, links.spacing, b, 0)     // one spacing of travel puts link 2 where link 3 was
        for (k in 0..3) assertEquals(a[k], b[k], 1e-3F)
        assertEquals(0F, a[2], 1e-3F)                // flat on the straight top run
        assertTrue(abs(a[3] - links.spacing / 4F) < 1e-3F)
    }

    @Test fun `degenerate input is refused`() {
        assertNull(TrackBeltPath.fromWheels(floatArrayOf(0F), floatArrayOf(0F), floatArrayOf(5F), false))
        assertNull(TrackBeltPath.fromWheels(floatArrayOf(0F, 0F), floatArrayOf(0F, 0F), floatArrayOf(5F, 5F), false))
    }
}
