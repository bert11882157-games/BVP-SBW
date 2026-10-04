package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import kotlin.math.roundToInt

/**
 * Reload length that grows with how much of the magazine has been fired (the owner's autocannon rule: 2 s after one
 * round, 4 s at 10%, 6 s at 25%, 8 s at 50%, 10 s at 75%, 12 s for an empty belt). The weapon lists the knots as
 * `DepletionReload: [[fraction fired, seconds], ...]`; the length is linear between them.
 */
object DepletionReload {
    const val TICKS_PER_SECOND = 20.0

    /**
     * Seconds for a reload with [fired] of [magazine] rounds gone. The fraction is measured so that one round fired
     * is the first knot and a fully fired magazine the last: `(fired - 1) / (magazine - 1)`.
     */
    @JvmStatic
    fun seconds(knots: List<List<Double>>, magazine: Int, fired: Int): Double? {
        val pts = knots.filter { it.size >= 2 }.map { it[0] to it[1] }.sortedBy { it.first }
        if (pts.isEmpty() || magazine <= 0) return null
        val f = if (magazine <= 1) 1.0 else ((fired - 1).coerceAtLeast(0).toDouble() / (magazine - 1)).coerceIn(0.0, 1.0)
        if (f <= pts.first().first) return pts.first().second
        for (i in 1 until pts.size) {
            val (x0, y0) = pts[i - 1]
            val (x1, y1) = pts[i]
            if (f <= x1) return if (x1 <= x0) y1 else y0 + (y1 - y0) * (f - x0) / (x1 - x0)
        }
        return pts.last().second
    }

    /**
     * Reload ticks for this gun's current magazine, or null when the gun has no depletion curve. [fullChange] (an
     * ammunition switch) times the reload as an empty belt, so creative's refilled magazine does not shorten it.
     */
    @JvmStatic
    @JvmOverloads
    fun ticks(data: GunData, fullChange: Boolean = false): Int? {
        val knots = data.getDefault().depletionReload
        if (knots.isEmpty()) return null
        val magazine = data.get(GunProp.MAGAZINE)
        val fired = if (fullChange) magazine else magazine - data.ammo.get().coerceIn(0, magazine)
        val s = seconds(knots, magazine, fired) ?: return null
        return (s * TICKS_PER_SECOND).roundToInt().coerceAtLeast(1)
    }
}
