package com.atsuishio.superbwarfare.client.input

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AircraftMouseFilterTest {
    @Test fun sustainedHumanSamplesAndTimingBurstsCannotAmplifyInput() {
        for (gain in listOf(0.0045, 0.0035)) for (sensitivity in listOf(0.25, 1.0, 2.0, 4.0)) {
            var activity = 0.0
            var filtered = 0.0
            repeat(72000) { tick ->
                // 60 minutes at 20 Hz, including coalesced cursor input and abrupt reversals.
                val pixels = when (tick % 500) {
                    in 0..149 -> 256.0
                    in 150..299 -> -256.0
                    in 300..399 -> if (tick % 2 == 0) 2.0 else -2.0
                    else -> 0.0
                }
                val speed = pixels * sensitivity
                activity += 0.1 * (speed - activity)
                val target = speed * 0.5
                val next = AircraftMouseFilter.advance(filtered, target, activity, gain, 0.1)
                assertTrue(next.isFinite())
                assertTrue(next >= min(filtered, target) - 1e-9 && next <= max(filtered, target) + 1e-9)
                filtered = next
            }
            repeat(400) { activity *= 0.9; filtered = AircraftMouseFilter.advance(filtered, 0.0, activity, gain, 0.1) }
            assertTrue(abs(filtered) < 1e-9)
        }
    }

    @Test fun reproducesUnboundedLegacyFilterAndRecoversInvalidState() {
        var legacy = 0.0
        var activity = 0.0
        repeat(500) {
            activity += 0.1 * (1024.0 - activity)
            legacy += max(0.1, 0.0045 * abs(activity)) * (512.0 - legacy)
        }
        assertTrue(!legacy.isFinite() || abs(legacy) > 1e20)
        assertEquals(4.0, AircraftMouseFilter.advance(Double.NaN, 4.0, 512.0, 0.0045, 0.1))
        assertEquals(0.0, AircraftMouseFilter.advance(4.0, Double.NaN, 512.0, 0.0045, 0.1))
    }
}
