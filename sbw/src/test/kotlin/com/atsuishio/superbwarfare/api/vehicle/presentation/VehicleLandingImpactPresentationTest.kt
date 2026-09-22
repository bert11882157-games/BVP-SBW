package com.atsuishio.superbwarfare.api.vehicle.presentation

import net.minecraft.util.Mth
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VehicleLandingImpactPresentationTest {
    private fun peak(speed: Double) = VehicleLandingImpactPresentation.amplitude(speed) * Mth.DEG_TO_RAD * 0.1

    @Test fun severityIsFiniteMonotonicAndClamped() {
        for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.0)) {
            assertEquals(0.0, VehicleLandingImpactPresentation.amplitude(speed))
        }
        assertEquals(0.1, peak(0.05), 1e-12)
        assertTrue(peak(0.25) > peak(0.05))
        assertEquals(0.65, peak(0.5), 1e-12)
        assertEquals(0.65, peak(Double.MAX_VALUE), 1e-12)
    }

    @Test fun existingImpulseIsShortAndIndependentOfOrdinaryFrameRate() {
        val afterOneSecond = mutableListOf<Double>()
        for (fps in listOf(30, 60, 120)) {
            var phase = VehicleLandingImpactPresentation.IMPULSE_PHASE
            var previous = 1.0
            for (frame in 1..fps * 3) {
                // The production camera uses deltaFrameTime in client ticks.
                phase += (0.0 - phase) * (0.05 * 20.0 / fps)
                val fraction = phase * sin(0.5 * PI * phase)
                assertTrue(fraction in 0.0..previous)
                assertTrue(fraction * peak(0.5) <= VehicleLandingImpactPresentation.MAX_PEAK_DEGREES)
                if (frame == fps) afterOneSecond += fraction
                if (frame == fps * 2) assertTrue(fraction < 0.03)
                if (frame == fps * 3) assertTrue(fraction < 0.004)
                previous = fraction
            }
        }
        assertTrue(afterOneSecond.max() - afterOneSecond.min() < 0.007)
    }

    @Test fun reducedUserStrengthScalesAmplitudeNotWorldPosition() {
        val source = doubleArrayOf(30000.0, 200.0, -17000.0)
        for (strength in listOf(0.0, 0.25, 0.5, 1.0)) {
            val position = source.copyOf()
            val distance = kotlin.math.sqrt(source.indices.sumOf { (source[it] - position[it]) * (source[it] - position[it]) })
            val attenuation = (1.0 - distance / VehicleLandingImpactPresentation.OCCUPANT_RADIUS).coerceIn(0.0, 1.0)
            assertEquals(1.0, attenuation)
            assertEquals(peak(0.25) * strength, abs(peak(0.25) * strength * attenuation), 1e-12)
        }
    }
}
