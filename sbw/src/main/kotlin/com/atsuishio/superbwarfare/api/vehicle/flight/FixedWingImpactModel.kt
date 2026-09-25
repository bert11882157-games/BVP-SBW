package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3
import kotlin.math.max

/** Ground-contact damage from velocity normal to the contacted surface, in game-world m/s. */
object FixedWingImpactModel {
    data class Damage(val healthFraction: Double, val destructive: Boolean)

    /** Hull or wing normal impact speed that always destroys the airframe. */
    const val BODY_LETHAL_NORMAL_SPEED = 6.0
    private const val BODY_SAFE_NORMAL_SPEED = 0.2
    /** Sink rate the deployed undercarriage absorbs without any damage. */
    const val GEAR_SAFE_SINK_SPEED = 3.0
    /** Sink rate that collapses the undercarriage and wrecks the aircraft. */
    const val GEAR_LETHAL_SINK_SPEED = 9.0
    private const val GEAR_MAX_SINK_DAMAGE = 0.6
    /** A tyre running into an obstacle face below this closing speed is harmless. */
    const val GEAR_STRIKE_SAFE_SPEED = 3.0
    /** Closing speed at which a tyre striking an obstacle face wrecks the aircraft. */
    const val GEAR_STRIKE_LETHAL_SPEED = 30.0
    private const val GEAR_MAX_STRIKE_DAMAGE = 0.5
    /** Hull contact slower than this while the wheels carry the aircraft is a runway scrape. */
    const val ROLL_SCRAPE_NORMAL_SPEED = 1.5
    private const val ROLL_SCRAPE_MAX_DAMAGE = 0.02
    /** Wing normal impact speed needed to tear a wing off; slower contact only scrapes it. */
    const val WING_SHEAR_NORMAL_SPEED = 3.0

    /** Missing lift is survivable in flight, but an uncontrolled hull strike is not a landing. */
    fun afterWingLoss(detachedWings: Int, complete: Boolean, bodyContact: Boolean,
                      gearImpactSpeed: Double, totalSpeedSquared: Double, ordinary: Damage): Damage {
        if (detachedWings == 0 || !complete) return ordinary
        // Retain harmless rest/slow placement and gentle wheel contact. Evaluate before
        // the sweep removes incoming motion, including gear-first crashes.
        return if ((bodyContact && totalSpeedSquared >= 4.0) || gearImpactSpeed >= 0.1)
            Damage(1.0, true) else ordinary
    }

    /**
     * [safeGearContact] grades a deployed-gear touchdown by sink rate. [groundRoll] marks hull
     * contact while the wheels carry the aircraft, where a slow tail or wingtip touch scrapes.
     */
    @JvmOverloads
    fun evaluate(normalSpeedSquared: Double, totalSpeedSquared: Double, safeGearContact: Boolean,
                 groundRoll: Boolean = false): Damage {
        require(normalSpeedSquared.isFinite() && normalSpeedSquared >= 0.0)
        require(totalSpeedSquared.isFinite() && totalSpeedSquared >= normalSpeedSquared)
        if (safeGearContact) return graded(normalSpeedSquared, GEAR_SAFE_SINK_SPEED,
            GEAR_LETHAL_SINK_SPEED, GEAR_MAX_SINK_DAMAGE)
        val lethalSquared = BODY_LETHAL_NORMAL_SPEED * BODY_LETHAL_NORMAL_SPEED
        if (normalSpeedSquared >= lethalSquared) return Damage(1.0, true)
        val safeSquared = BODY_SAFE_NORMAL_SPEED * BODY_SAFE_NORMAL_SPEED
        val impact = ((normalSpeedSquared - safeSquared) / (lethalSquared - safeSquared)).coerceIn(0.0, 1.0)
        if (groundRoll && normalSpeedSquared < ROLL_SCRAPE_NORMAL_SPEED * ROLL_SCRAPE_NORMAL_SPEED) {
            // A tail bumper or wingtip brushing the runway during rotation or flare.
            val scrape = if (totalSpeedSquared >= 4.0) 0.002 + 0.01 * totalSpeedSquared / 3600.0 else 0.0
            return Damage(max(impact, scrape).coerceAtMost(ROLL_SCRAPE_MAX_DAMAGE), false)
        }
        // Sliding a wing or fuselage along terrain spends energy even with little normal motion.
        val scrape = if (totalSpeedSquared >= 4.0) {
            (0.005 + 0.1 * totalSpeedSquared / 3600.0).coerceAtMost(0.25)
        } else 0.0
        return Damage(max(impact, scrape), false)
    }

    /** A tyre running into an obstacle face it cannot climb: damage grows with closing energy. */
    fun gearStrike(normalSpeedSquared: Double): Damage {
        require(normalSpeedSquared.isFinite() && normalSpeedSquared >= 0.0)
        return graded(normalSpeedSquared, GEAR_STRIKE_SAFE_SPEED, GEAR_STRIKE_LETHAL_SPEED,
            GEAR_MAX_STRIKE_DAMAGE)
    }

    /** Velocities in blocks per tick; the normal points out of the struck terrain face. */
    fun shearsWing(incoming: Vec3, contactNormal: Vec3): Boolean =
        max(0.0, -incoming.dot(contactNormal)) * 20.0 >= WING_SHEAR_NORMAL_SPEED

    fun strongest(first: Damage, second: Damage): Damage =
        Damage(max(first.healthFraction, second.healthFraction), first.destructive || second.destructive)

    private fun graded(normalSpeedSquared: Double, safe: Double, lethal: Double, maximum: Double): Damage {
        if (normalSpeedSquared >= lethal * lethal) return Damage(1.0, true)
        val fraction = ((normalSpeedSquared - safe * safe) / (lethal * lethal - safe * safe)).coerceIn(0.0, 1.0)
        return Damage(maximum * fraction, false)
    }
}
