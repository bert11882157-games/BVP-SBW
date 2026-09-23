package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.max

/** Ground-contact damage from velocity normal to the contacted surface, in game-world m/s. */
object FixedWingImpactModel {
    data class Damage(val healthFraction: Double, val destructive: Boolean)

    /** Missing lift is survivable in flight, but an uncontrolled hull strike is not a landing. */
    fun afterWingLoss(detachedWings: Int, complete: Boolean, bodyContact: Boolean,
                      gearImpactSpeed: Double, totalSpeedSquared: Double, ordinary: Damage): Damage {
        if (detachedWings == 0 || !complete) return ordinary
        // Retain harmless rest/slow placement and gentle wheel contact. Evaluate before
        // the sweep removes incoming motion, including gear-first crashes.
        return if ((bodyContact && totalSpeedSquared >= 4.0) || gearImpactSpeed >= 0.1)
            Damage(1.0, true) else ordinary
    }

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
