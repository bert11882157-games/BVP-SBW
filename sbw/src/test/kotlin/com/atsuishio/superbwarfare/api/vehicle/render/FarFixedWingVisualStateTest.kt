package com.atsuishio.superbwarfare.api.vehicle.render

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarFixedWingVisualStateTest {
    @Test fun `airbrake survives far transfer and interpolates while legacy payloads remain readable`() {
        val a = FarFixedWingVisualState.create(1, 10, 0F, 0F, 0F, 0.0, 0F)!!
        val b = FarFixedWingVisualState.create(2, 13, 0F, 0F, 0F, 0.0, 1F)!!
        assertEquals(b, FarFixedWingVisualState.decode(b.encode()))
        assertEquals(0.5F, FarFixedWingVisualState.interpolate(a, b, 0.5F)!!.airbrake)
        assertEquals(0F, FarFixedWingVisualState.decode("1;1;10;0;0;0;0")!!.airbrake)
        assertNull(FarFixedWingVisualState.decode("2;1;10;0;0;0;0;NaN"))
        assertNull(FarFixedWingVisualState.decode("2;1;10;0;0;0;0;1.01"))
    }
    private fun state(sequence: Int = 1, tick: Long = 20, elevator: Float = -1F,
                      aileron: Float = 0.5F, rudder: Float = -0.25F, throttle: Double = 0.7) =
        FarFixedWingVisualState.create(sequence, tick, elevator, aileron, rudder, throttle)!!

    @Test fun `bounded complete accepted tuple round trips including signed source wrap`() {
        for (sequence in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            for (tick in listOf(0L, 20L, Long.MAX_VALUE)) {
                for (axis in listOf(-1F, -Float.MIN_VALUE, 0F, Float.MIN_VALUE, 1F)) {
                    for (throttle in listOf(0.0, Double.MIN_VALUE, 0.37, 1.0)) {
                        val value = state(sequence, tick, axis, -axis, axis, throttle)
                        assertEquals(value, FarFixedWingVisualState.decode(value.encode()))
                        assertTrue(value.encode().length <= FarFixedWingVisualState.MAX_TEXT_LENGTH)
                    }
                }
            }
        }
    }

    @Test fun `malformed oversized partial and nonfinite tuples cannot enter presentation`() {
        for (text in listOf(null, "", "1", "2;1;20;0;0;0;1", "1;1;20;0;0;0",
                "1;1;20;0;0;0;1;extra", "1;2147483648;20;0;0;0;1",
                "1;-2147483649;20;0;0;0;1", "1;1;-1;0;0;0;1",
                "1;1;9223372036854775808;0;0;0;1", "1;1;20;NaN;0;0;1",
                "1;1;20;0;Infinity;0;1", "1;1;20;0;0;-1.001;1",
                "1;1;20;1.01;0;0;1", "1;1;20;0;0;0;NaN", "1;1;20;0;0;0;1.01",
                "1;1;20;0;0;0;-0.01", "x".repeat(193))) {
            assertNull(FarFixedWingVisualState.decode(text), text)
        }
        assertNull(FarFixedWingVisualState.create(1, -1, 0F, 0F, 0F, 0.0))
        assertNull(FarFixedWingVisualState.create(1, 1, 0F, Float.NaN, 0F, 0.0))
        assertThrows(IllegalArgumentException::class.java) { state().copy(rudder = 2F) }
        assertThrows(IllegalArgumentException::class.java) { state().copy(throttle = Double.NaN) }
    }

    @Test fun `accepted axes and spool share one bounded far interpolation clock at render rate`() {
        val previous = state(10, 100, -1F, 1F, -0.5F, 0.0)
        val current = state(11, 103, 1F, -1F, 0.5F, 1.0)
        for (fps in listOf(30, 60, 120)) {
            val frames = fps * 3 / 20
            var last = -1F
            for (frame in 0..frames) {
                val a = frame.toFloat() / frames
                val value = FarFixedWingVisualState.interpolate(previous, current, a)!!
                assertEquals(-1F + 2F * a, value.elevator, 1e-6F)
                assertEquals(1F - 2F * a, value.aileron, 1e-6F)
                assertEquals(-0.5F + a, value.rudder, 1e-6F)
                assertEquals(a.toDouble(), value.throttle, 1e-6)
                assertTrue(value.elevator >= last)
                assertEquals(value, FarFixedWingVisualState.interpolate(previous, current, a))
                last = value.elevator
            }
        }
        assertEquals(previous, FarFixedWingVisualState.interpolate(previous, current, -10F))
        assertEquals(current, FarFixedWingVisualState.interpolate(previous, current, 10F))
        assertNull(FarFixedWingVisualState.interpolate(previous, current, Float.NaN))
    }

    @Test fun `omission reacquisition and source reset never blend stale accepted controls`() {
        val previous = state(10, 100)
        val reset = state(0, 0, 0.8F, 0F, 0F, 0.2)
        assertNull(FarFixedWingVisualState.interpolate(previous, null, 0F))
        assertEquals(reset, FarFixedWingVisualState.interpolate(null, reset, 0F))
        assertEquals(reset, FarFixedWingVisualState.interpolate(previous, reset, 0F))
        assertEquals(previous, FarFixedWingVisualState.interpolate(previous, previous, 0F))
        val sameTick = state(11, 100, 0F)
        assertEquals(sameTick, FarFixedWingVisualState.interpolate(previous, sameTick, 0F))
        val olderSequence = state(9, 101, 0F)
        assertEquals(olderSequence, FarFixedWingVisualState.interpolate(previous, olderSequence, 0F))
    }

    @Test fun `normal signed sequence wrap still interpolates the accepted bracket`() {
        val previous = state(Int.MAX_VALUE, 100, -1F, 0F, 0F, 0.0)
        val current = state(Int.MIN_VALUE, 103, 1F, 0F, 0F, 1.0)
        val value = FarFixedWingVisualState.interpolate(previous, current, 0.5F)!!
        assertEquals(0F, value.elevator)
        assertEquals(0.5, value.throttle)
        assertEquals(Int.MIN_VALUE, value.sequence)
        assertEquals(103L, value.serverTick)
    }
}
