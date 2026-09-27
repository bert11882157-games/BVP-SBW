package com.atsuishio.superbwarfare.tools.blast

import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Timeline and sizing of the client blast presentation, all derived from the TNT charge W (kg) through
 * s = W^(1/3) (so the fireball radius is k * s). Times are in client ticks (1/20 s), lengths in blocks.
 *
 * - The fireball starts at the impact point and expands to its radius in [expansionTicks]: one tick (instant)
 *   for small charges, about 0.4 s for multi-ton bombs, always decelerating (ease-out).
 * - It then holds briefly and burns out over [fireFadeTicks]; bigger charges burn longer.
 * - Light grey smoke in the central region outlasts the fire briefly and thins out ([smokeTicks]: about 4 s for a
 *   Mk 82, 8 s for a FAB-3000).
 * - Charges of [MUSHROOM_KG] and more also raise a mushroom cloud.
 * - Ground bursts throw terrain chunks: bigger charges throw larger AND more chunks, faster.
 */
object BlastVisuals {
    const val MUSHROOM_KG = 1000.0
    /** Smallest drawn fireball radius, so 30 mm and other sub-kilogram rounds still read as explosions. */
    const val MIN_VISUAL_RADIUS = 0.35
    const val MAX_CHUNKS = 120

    @JvmStatic
    fun chargeFromFireballRadius(fireballRadius: Double, k: Double = BlastParameters.DEFAULT.fireballK): Double {
        // The server boosts blast radii by charge (BlastModel.radiusBoost); undo it so timings follow the true charge.
        return BlastModel.chargeForRadius(fireballRadius, k)
    }

    @JvmStatic
    fun scale(kg: Double): Double = if (kg.isFinite() && kg > 0.0) cbrt(kg) else 0.0

    @JvmStatic
    fun visualRadius(fireballRadius: Double): Double = max(fireballRadius, MIN_VISUAL_RADIUS)

    @JvmStatic
    fun expansionTicks(kg: Double): Double = (0.6 * scale(kg)).coerceIn(1.0, 9.0)

    /** Expansion fraction 0..1 at [ageTicks]: ease-out cubic, so the front is fastest at the start. */
    @JvmStatic
    fun expansionAt(ageTicks: Double, kg: Double): Double {
        if (!(ageTicks > 0.0)) return 0.0
        val t = (ageTicks / expansionTicks(kg)).coerceIn(0.0, 1.0)
        val inverse = 1.0 - t
        return 1.0 - inverse * inverse * inverse
    }

    @JvmStatic
    fun fireHoldTicks(kg: Double): Double = 2.0 + 0.8 * expansionTicks(kg)

    @JvmStatic
    fun fireFadeTicks(kg: Double): Double = 6.0 + 2.4 * scale(kg)

    @JvmStatic
    fun fireEndTicks(kg: Double): Double = expansionTicks(kg) + fireHoldTicks(kg) + fireFadeTicks(kg)

    @JvmStatic
    fun flashTicks(kg: Double): Double = 2.0 + 0.4 * scale(kg)

    @JvmStatic
    fun smokeTicks(kg: Double): Double = 36.0 + 10.0 * scale(kg)

    @JvmStatic
    fun dustTicks(kg: Double): Double = 30.0 + 7.0 * scale(kg)

    @JvmStatic
    fun producesMushroom(kg: Double): Boolean = kg.isFinite() && kg >= MUSHROOM_KG

    @JvmStatic
    fun mushroomTicks(kg: Double): Double = (12.0 + 0.5 * scale(kg)) * 20.0

    /** Rise time constant of the mushroom cap, in ticks. */
    @JvmStatic
    fun mushroomRiseTauTicks(kg: Double): Double = (2.5 + 0.08 * scale(kg)) * 20.0

    /** Fraction 0..1 of the mushroom cap's final rise reached at [ticksSinceStart]. */
    @JvmStatic
    fun mushroomRiseAt(ticksSinceStart: Double, kg: Double): Double =
        if (ticksSinceStart <= 0.0) 0.0 else 1.0 - exp(-ticksSinceStart / mushroomRiseTauTicks(kg))

    @JvmStatic
    fun chunkCount(kg: Double): Int = if (scale(kg) <= 0.0) 0 else (10.0 + 5.0 * scale(kg)).roundToInt().coerceAtMost(MAX_CHUNKS)

    /** Mean chunk edge length in blocks. */
    @JvmStatic
    fun chunkSize(kg: Double): Double = 0.1 + 0.07 * scale(kg)

    /** Mean chunk launch speed in blocks per second. */
    @JvmStatic
    fun chunkSpeed(kg: Double): Double = 6.0 + 3.5 * sqrt(scale(kg))

    @JvmStatic
    fun firePuffCount(visualRadius: Double): Int = (12.0 + 10.0 * visualRadius).roundToInt().coerceIn(12, 90)

    @JvmStatic
    fun smokePuffCount(visualRadius: Double): Int = (10.0 + 9.0 * visualRadius).roundToInt().coerceIn(10, 110)

    @JvmStatic
    fun dustPuffCount(visualRadius: Double): Int = (8.0 + 6.0 * visualRadius).roundToInt().coerceIn(8, 60)

    /** Time the whole presentation needs, in ticks. */
    @JvmStatic
    fun totalTicks(kg: Double): Double {
        var end = max(fireEndTicks(kg), max(smokeTicks(kg), dustTicks(kg)) + expansionTicks(kg))
        if (producesMushroom(kg)) end = max(end, expansionTicks(kg) + mushroomTicks(kg))
        return end + 220.0 // landed chunks linger
    }
}
