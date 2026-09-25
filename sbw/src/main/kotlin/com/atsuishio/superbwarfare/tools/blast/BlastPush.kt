package com.atsuishio.superbwarfare.tools.blast

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How hard a TNT blast shoves an armored ground vehicle. Light vehicles (IFVs, APCs, light tanks, cars, below
 * [HEAVY_TONNES]) start moving at [LIGHT_THRESHOLD_KG], main battle tanks at [HEAVY_THRESHOLD_KG].
 * From a mild nudge at the threshold the push grows as (W / threshold)^0.8: about 6-7 m/s for a 500 kg charge
 * against a 50 t tank (thrown), about 20 m/s for a FAB-5000 (hurled), and falls off quadratically to zero at
 * the severe radius. Heavier vehicles in a class move a little less.
 */
object BlastPush {
    const val LIGHT_THRESHOLD_KG = 5.0
    const val HEAVY_THRESHOLD_KG = 20.0
    const val HEAVY_TONNES = 30.0
    const val MAX_SPEED_MPS = 32.0
    private const val BASE_SPEED_MPS = 0.5
    private const val GROWTH = 0.8

    @JvmStatic
    fun threshold(massTonnes: Double): Double =
        if (massTonnes.isFinite() && massTonnes >= HEAVY_TONNES) HEAVY_THRESHOLD_KG else LIGHT_THRESHOLD_KG

    /** Fraction 1 at the charge, 0 at and beyond [pushRadius]. */
    @JvmStatic
    fun falloff(distance: Double, pushRadius: Double): Double {
        if (!(pushRadius > 0.0) || !distance.isFinite()) return 0.0
        val f = (1.0 - distance.coerceAtLeast(0.0) / pushRadius).coerceIn(0.0, 1.0)
        return f * f
    }

    /** Push speed in m/s, 0 below the vehicle's class threshold or outside the push radius. */
    @JvmStatic
    fun speedMps(kg: Double, distance: Double, pushRadius: Double, massTonnes: Double): Double {
        val threshold = threshold(massTonnes)
        if (!kg.isFinite() || kg < threshold) return 0.0
        val f = falloff(distance, pushRadius)
        if (f <= 0.0) return 0.0
        val reference = if (threshold == HEAVY_THRESHOLD_KG) 50.0 else 20.0
        val mass = if (massTonnes.isFinite() && massTonnes > 0.0) massTonnes else reference
        val massFactor = sqrt(reference / mass).coerceIn(0.6, 1.6)
        return (BASE_SPEED_MPS * (kg / threshold).pow(GROWTH) * f * massFactor).coerceAtMost(MAX_SPEED_MPS)
    }

    /** Upward share of the push direction: more lift close to the charge. */
    @JvmStatic
    fun liftShare(distance: Double, pushRadius: Double): Double = 0.35 + 0.65 * sqrt(falloff(distance, pushRadius))
}
