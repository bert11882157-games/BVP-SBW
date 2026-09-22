package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.max

/** Ground-contact damage from velocity normal to the contacted surface, in game-world m/s. */
object FixedWingImpactModel {
    data class Damage(val healthFraction: Double, val destructive: Boolean)

    fun evaluate(normalSpeedSquared: Double, totalSpeedSquared: Double, safeGearContact: Boolean): Damage {
        require(normalSpeedSquared.isFinite() && normalSpeedSquared >= 0.0)
        require(totalSpeedSquared.isFinite() && totalSpeedSquared >= normalSpeedSquared)
        val destructive = normalSpeedSquared >= 36.0
        if (destructive) return Damage(1.0, true)
        // Let deployed wheels absorb gentle touchdown, including the normal half-block
        // placement drop. Belly/wing impacts keep their separate low tolerance.
        val safeSquared = if (safeGearContact) 4.0 else 0.04
        val impact = ((normalSpeedSquared - safeSquared) / (36.0 - safeSquared)).coerceIn(0.0, 1.0)
        // Sliding a wing or fuselage along terrain spends energy even with little normal motion.
        val scrape = if (!safeGearContact && totalSpeedSquared >= 4.0) {
            (0.005 + 0.1 * totalSpeedSquared / 3600.0).coerceAtMost(0.25)
        } else 0.0
        return Damage(max(impact, scrape), false)
    }
}
