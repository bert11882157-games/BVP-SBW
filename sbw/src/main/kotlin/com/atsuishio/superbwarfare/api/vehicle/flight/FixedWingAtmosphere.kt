package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fixed-wing-only standard atmosphere. Altitude is reference metres above sea level.
 * Indicated limits use an ideal calibrated pitot, not the low-Mach EAS approximation.
 */
object FixedWingAtmosphere {
    private const val SEA_TEMPERATURE = 288.15
    private const val GAS_CONSTANT = 287.05287
    private const val GAMMA = 1.4
    private val seaSoundSpeed = sqrt(GAMMA * GAS_CONSTANT * SEA_TEMPERATURE)

    @JvmStatic
    fun temperatureKelvin(referenceAltitudeMetres: Double): Double {
        require(referenceAltitudeMetres.isFinite())
        val h = referenceAltitudeMetres.coerceIn(-500.0, 20000.0)
        return if (h <= 11000.0) SEA_TEMPERATURE - 0.0065 * h else 216.65
    }

    @JvmStatic
    fun densityRatio(referenceAltitudeMetres: Double): Double {
        require(referenceAltitudeMetres.isFinite())
        val h = referenceAltitudeMetres.coerceIn(-500.0, 20000.0)
        val ratio = if (h <= 11000.0) {
            (1.0 - 0.0065 * h / SEA_TEMPERATURE).pow(4.2558797)
        } else {
            (216.65 / SEA_TEMPERATURE).pow(4.2558797) *
                exp(-(h - 11000.0) / 6341.62)
        }
        return ratio.coerceIn(0.02, 1.5)
    }

    /** One bounded conversion per tick, outside the force-substep loop. */
    @JvmStatic
    fun trueSpeedForIndicatedLimit(
        indicatedSpeedMps: Double,
        densityRatio: Double,
        temperatureKelvin: Double,
    ): Double {
        if (!indicatedSpeedMps.isFinite() || indicatedSpeedMps <= 0.0 ||
            !densityRatio.isFinite() || densityRatio !in 0.02..1.5 ||
            !temperatureKelvin.isFinite() || temperatureKelvin !in 160.0..330.0
        ) return Double.NaN
        if (densityRatio == 1.0 && temperatureKelvin == SEA_TEMPERATURE) {
            return indicatedSpeedMps
        }
        val pressureRatio = densityRatio * temperatureKelvin / SEA_TEMPERATURE
        val requiredImpactRatio =
            pitotImpactRatio(indicatedSpeedMps / seaSoundSpeed) / pressureRatio
        if (!requiredImpactRatio.isFinite()) return Double.NaN
        val mach = if (requiredImpactRatio <= pitotImpactRatio(1.0)) {
            sqrt(5.0 * ((1.0 + requiredImpactRatio).pow(2.0 / 7.0) - 1.0))
        } else {
            if (requiredImpactRatio > pitotImpactRatio(16.0)) return Double.NaN
            var low = 1.0
            var high = 16.0
            repeat(24) {
                val middle = (low + high) * 0.5
                if (pitotImpactRatio(middle) < requiredImpactRatio) {
                    low = middle
                } else {
                    high = middle
                }
            }
            (low + high) * 0.5
        }
        return mach * sqrt(GAMMA * GAS_CONSTANT * temperatureKelvin)
    }

    private fun pitotImpactRatio(mach: Double): Double {
        val m2 = mach * mach
        return if (mach <= 1.0) {
            (1.0 + 0.2 * m2).pow(3.5) - 1.0
        } else {
            (1.2 * m2).pow(3.5) *
                (2.4 / (2.8 * m2 - 0.4)).pow(2.5) - 1.0
        }
    }
}
