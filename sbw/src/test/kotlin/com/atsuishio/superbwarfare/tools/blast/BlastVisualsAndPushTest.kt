package com.atsuishio.superbwarfare.tools.blast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlastVisualsAndPushTest {
    @Test
    fun expansionIsInstantForSmallAndPerceivableButFastForLarge() {
        assertEquals(1.0, BlastVisuals.expansionTicks(0.0179), 1e-9)
        assertEquals(1.0, BlastVisuals.expansionTicks(1.0), 1e-9)
        assertEquals(1.0, BlastVisuals.expansionAt(1.0, 1.0), 1e-9)
        val fab3000 = BlastVisuals.expansionTicks(2219.0)
        assertTrue(fab3000 in 6.0..9.0, "FAB-3000 expansion $fab3000 ticks")
        // Ease-out: well past half the radius after a quarter of the expansion time.
        assertTrue(BlastVisuals.expansionAt(fab3000 * 0.25, 2219.0) > 0.55)
        assertEquals(0.0, BlastVisuals.expansionAt(0.0, 2219.0), 1e-12)
    }

    @Test
    fun biggerChargesLastLongerAndThrowBiggerAndMoreChunks() {
        val charges = listOf(0.5, 5.0, 117.6, 500.0, 2219.0)
        for ((a, b) in charges.zipWithNext()) {
            assertTrue(BlastVisuals.fireEndTicks(b) > BlastVisuals.fireEndTicks(a))
            assertTrue(BlastVisuals.smokeTicks(b) > BlastVisuals.smokeTicks(a))
            assertTrue(BlastVisuals.chunkSize(b) > BlastVisuals.chunkSize(a))
            assertTrue(BlastVisuals.chunkCount(b) >= BlastVisuals.chunkCount(a))
            assertTrue(BlastVisuals.chunkSpeed(b) > BlastVisuals.chunkSpeed(a))
        }
        // Size grows faster than count: a FAB-3000 throws ~7x larger chunks but only ~3x as many as 5 kg.
        val sizeRatio = BlastVisuals.chunkSize(2219.0) / BlastVisuals.chunkSize(5.0)
        val countRatio = BlastVisuals.chunkCount(2219.0).toDouble() / BlastVisuals.chunkCount(5.0)
        assertTrue(sizeRatio > countRatio, "size x$sizeRatio count x$countRatio")
        assertFalse(BlastVisuals.producesMushroom(999.0))
        assertTrue(BlastVisuals.producesMushroom(1000.0))
        assertEquals(117.6, BlastVisuals.chargeFromFireballRadius(BlastModel.radius(0.5, 117.6)), 1e-6)
    }

    @Test
    fun pushThresholdsAndGrowth() {
        // IFV class (< 30 t) from 5 kg, MBT class from 20 kg.
        assertEquals(0.0, BlastPush.speedMps(4.9, 0.0, 10.0, 20.0))
        assertTrue(BlastPush.speedMps(5.0, 0.0, 10.0, 20.0) in 0.3..0.8)
        assertEquals(0.0, BlastPush.speedMps(19.0, 0.0, 10.0, 50.0))
        assertTrue(BlastPush.speedMps(20.0, 0.0, 10.0, 50.0) in 0.3..0.8)
        val thrown = BlastPush.speedMps(500.0, 0.0, 14.0, 50.0)
        val hurled = BlastPush.speedMps(3000.0, 0.0, 26.0, 50.0)
        assertTrue(thrown in 5.0..9.0, "500 kg vs 50 t: $thrown m/s")
        assertTrue(hurled > 20.0, "FAB-5000 class vs 50 t: $hurled m/s")
        // Closer is stronger; nothing at the push radius.
        assertTrue(BlastPush.speedMps(500.0, 2.0, 14.0, 50.0) > BlastPush.speedMps(500.0, 8.0, 14.0, 50.0))
        assertEquals(0.0, BlastPush.speedMps(500.0, 14.0, 14.0, 50.0))
        assertTrue(BlastPush.liftShare(0.0, 14.0) > BlastPush.liftShare(10.0, 14.0))
    }
}
