package com.atsuishio.superbwarfare.api.aircraft

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftClusterBombTest {
    @Test fun spreadIsBoundedDistinctAndRepeatable() {
        val points = AircraftClusterBomb.spread(12, .35, .2)
        assertEquals(12, points.size)
        assertEquals(12, points.toSet().size)
        assertEquals(points, AircraftClusterBomb.spread(12, .35, .2))
        assertTrue(points.all { it.horizontalDistance() <= .35 && it.y < 0 })
        assertTrue(points.any { it.x > 0 } && points.any { it.x < 0 })
        assertTrue(points.any { it.z > 0 } && points.any { it.z < 0 })
    }
    @Test fun configurationRejectsUnboundedCountsLifetimesAndInvalidNumbers() {
        fun config(count: Int = 12, life: Int = 100, spread: Double = .35) =
            AircraftClusterBomb.Configuration(count, 12.0, spread, 80f, 1.5f, life)
        assertEquals(12, config().count)
        assertThrows(IllegalArgumentException::class.java) { config(count = 25) }
        assertThrows(IllegalArgumentException::class.java) { config(life = 201) }
        assertThrows(IllegalArgumentException::class.java) { config(spread = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { AircraftClusterBomb.spread(10000, .35, 0.0) }
    }
}
