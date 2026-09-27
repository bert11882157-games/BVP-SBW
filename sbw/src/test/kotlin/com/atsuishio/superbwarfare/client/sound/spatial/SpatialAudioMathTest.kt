package com.atsuishio.superbwarfare.client.sound.spatial

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class SpatialAudioMathTest {
    @Test fun `gain is full inside the reference distance and follows sqrt beyond it`() {
        assertEquals(1.0, SpatialAudioPlayer.gainAt(2.0, 240.0), 1e-9)
        assertEquals(sqrt(6.0 / 24.0), SpatialAudioPlayer.gainAt(24.0, 240.0), 1e-9)
        assertEquals(0.5, SpatialAudioPlayer.gainAt(24.0, 240.0), 1e-9)
    }

    @Test fun `gain fades to silence at the maximum hearing distance`() {
        assertEquals(0.0, SpatialAudioPlayer.gainAt(240.0, 240.0), 1e-9)
        assertTrue(SpatialAudioPlayer.gainAt(200.0, 240.0) < SpatialAudioPlayer.gainAt(170.0, 240.0))
        var last = 2.0
        for (d in 0..240 step 4) {
            val g = SpatialAudioPlayer.gainAt(d.toDouble(), 240.0)
            assertTrue(g <= last + 1e-12, "monotonic at $d")
            last = g
        }
    }

    @Test fun `band weights sum to one and cross over at the band edges`() {
        for (d in 0..240 step 3) {
            val w = SpatialAudioPlayer.bandWeights(d.toDouble(), 96.0, 168.0, 240.0)
            assertEquals(1.0, w.sum(), 1e-9, "sum at $d")
            assertTrue(w.all { it >= 0.0 })
        }
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), SpatialAudioPlayer.bandWeights(10.0, 96.0, 168.0, 240.0), 1e-9)
        assertEquals(0.5, SpatialAudioPlayer.bandWeights(96.0, 96.0, 168.0, 240.0)[0], 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 1.0), SpatialAudioPlayer.bandWeights(230.0, 96.0, 168.0, 240.0), 1e-9)
    }

    @Test fun `missing variants hand their weight to the nearest authored one`() {
        val w = doubleArrayOf(0.2, 0.5, 0.3)
        assertArrayEquals(doubleArrayOf(0.0, 0.7, 0.3), SpatialAudioPlayer.remap(w, booleanArrayOf(false, true, true)), 1e-9)
        assertArrayEquals(doubleArrayOf(0.2, 0.0, 0.8), SpatialAudioPlayer.remap(w, booleanArrayOf(true, false, true)), 1e-9)
        assertArrayEquals(doubleArrayOf(0.2, 0.8, 0.0), SpatialAudioPlayer.remap(w, booleanArrayOf(true, true, false)), 1e-9)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0), SpatialAudioPlayer.remap(w, booleanArrayOf(true, false, false)), 1e-9)
    }
}
