package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance.Figure
import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance.Stats
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftStorePerformanceTextTest {
    @Test fun figuresAreShownInMetricUnits() {
        val lines = AircraftStorePerformanceText.lines(Stats(Figure.of(70.0), Figure.of(15.56), Figure.of(0.1), Figure.of(9.0)))
        assertEquals(listOf("Turn rate: 70°/s", "Explosive mass: 15.6 kg TNT",
            "Acceleration: 40 m/s² (4.08 g)", "Top speed: 180 m/s (648 km/h)"), lines.map { it.toString() })
        assertTrue(lines.all { it.known })
    }

    @Test fun missingFiguresShowTheirReason() {
        val lines = AircraftStorePerformanceText.lines(Stats(Figure.none("Unguided"), Figure.of(675.0),
            Figure.none("Unpowered"), Figure.none("Free fall")))
        assertEquals("Turn rate: Unguided", lines[0].toString()); assertFalse(lines[0].known)
        assertEquals("Explosive mass: 675 kg TNT", lines[1].toString()); assertTrue(lines[1].known)
    }

    @Test fun numbersKeepAboutThreeSignificantFigures() {
        assertEquals("0", AircraftStorePerformanceText.number(0.0))
        assertEquals("1.15", AircraftStorePerformanceText.number(1.15))
        assertEquals("2.5", AircraftStorePerformanceText.number(2.5))
        assertEquals("10", AircraftStorePerformanceText.number(10.0))
        assertEquals("12.3", AircraftStorePerformanceText.number(12.34))
        assertEquals("648", AircraftStorePerformanceText.number(648.0))
    }
}
