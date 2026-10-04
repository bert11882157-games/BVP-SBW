package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * Vehicle-against-vehicle impacts (owner 2026-09-29): one momentum-conserving exchange per pair and contact, and
 * damage from the energy the crash absorbs, judged against each vehicle's size.
 *
 * Replaces the old strike code, whose push was a multiple (up to 3.2x) of the closing speed along the pusher's
 * heading and whose damage was closing speed squared times a clamped mass ratio, applied by both vehicles, every tick.
 *
 * Units: velocities in blocks/tick (1 b/t = 20 m/s), masses in tonnes (the vehicle data "Mass"), energy in kJ.
 *
 * - Impulse: only the closing speed along the contact normal counts (a sideswipe is not a head-on), restitution
 *   [RESTITUTION]. The change of velocity of either vehicle can never exceed (1 + e) x closing speed; the one impulse
 *   is limited so neither exceeds [MAX_GROUND_DELTA] / [MAX_AIRCRAFT_DELTA], keeping momentum conserved.
 * - Damage: absorbed energy E = 1/2 mu (v - v0)^2 (1 - e^2), mu the reduced mass, v0 = [HARMLESS_SPEED_MPS].
 *   Each vehicle takes the share the other one's mass stands for (the lighter one decelerates harder), as a fraction
 *   of its health: share / (toughness x own mass). Equal vehicles: the fraction only depends on closing speed.
 * - Size protection: a vehicle more than twice as heavy as what hit it cannot be destroyed by it (nor lose a wing);
 *   its damage is capped between [HEAVY_CAP_MIN] (the other one is a tenth of its mass or less) and nearly full
 *   (half of its mass), and it is left with at least 1 health. A fighter ramming a heavy bomber wrecks the fighter;
 *   two comparable aircraft meeting head-on both go down.
 * - Aircraft zones: a wing (or tail plane) strike damages the hull at [WING_HULL_FACTOR] and tears the wing off at
 *   [WING_SHEAR_FRACTION]; landing gear only scrapes ([GEAR_FACTOR], never lethal).
 */
internal object VehicleImpactModel {
    const val RESTITUTION = 0.15
    /** Closing speed (m/s) that does no damage: parking bumps, formation touches. */
    const val HARMLESS_SPEED_MPS = 3.0
    /** kJ per tonne that uses up all of a ground vehicle's health. */
    const val GROUND_TOUGHNESS = 150.0
    /** kJ per tonne that uses up all of an aircraft's health (airframes are lighter built than hulls). */
    const val AIRCRAFT_TOUGHNESS = 100.0
    const val HEAVY_CAP_MIN = 0.3
    const val WING_HULL_FACTOR = 0.5
    const val WING_SHEAR_FRACTION = 0.4
    const val GEAR_FACTOR = 0.15
    const val GEAR_CAP = 0.5
    /** Largest velocity change a collision gives a ground vehicle (blocks/tick, 20 m/s). */
    const val MAX_GROUND_DELTA = 1.0
    /** Largest velocity change a collision gives an aircraft (blocks/tick, 50 m/s). */
    const val MAX_AIRCRAFT_DELTA = 2.5
    /** Target separation speed for vehicles found inside each other (blocks/tick). */
    const val SEPARATION_SPEED = 0.1

    enum class Zone { BODY, WING, GEAR }

    data class Body(
        /** Tonnes. */
        val mass: Double,
        /** Blocks/tick. */
        val velocity: Vec3,
        val aircraft: Boolean,
        /** Parts of this vehicle that took the hit. */
        val zones: Set<Zone> = setOf(Zone.BODY),
    )

    data class Outcome(
        /** Velocity change of A and of B (blocks/tick). */
        val deltaA: Vec3,
        val deltaB: Vec3,
        /** Normal closing speed before the exchange (blocks/tick, <= 0 when separating). */
        val closingSpeed: Double,
        /** Fraction of max health each loses (0 = none, >= 1 = destroyed). */
        val damageA: Double,
        val damageB: Double,
        val lethalA: Boolean,
        val lethalB: Boolean,
        val shearA: Boolean,
        val shearB: Boolean,
        /** Much heavier than what hit it: this collision must leave it alive. */
        val protectedA: Boolean = false,
        val protectedB: Boolean = false,
    ) {
        val damages: Boolean get() = damageA > 0.0 || damageB > 0.0
    }

    /**
     * [normal] points from B toward A (unit). [overlapDepth] > 0 when the two are already inside each other: they are
     * then eased apart at [SEPARATION_SPEED] instead of being thrown.
     */
    fun resolve(a: Body, b: Body, normal: Vec3, overlapDepth: Double = 0.0): Outcome {
        val n = normal.normalize()
        val mA = safeMass(a.mass)
        val mB = safeMass(b.mass)
        val inverseSum = 1.0 / mA + 1.0 / mB
        val closing = -a.velocity.subtract(b.velocity).dot(n)
        // Collision impulse along n, then easing apart if already inside each other; both caps limit this one
        // impulse, so momentum stays conserved and a capped vehicle never receives more than the other gives.
        var impulse = if (closing > 0.0) (1.0 + RESTITUTION) * closing / inverseSum else 0.0
        if (overlapDepth > 0.0) {
            // Bring the separation speed up to the target, never beyond it.
            val separating = max(closing, 0.0) * RESTITUTION - min(closing, 0.0)
            val wanted = min(SEPARATION_SPEED, overlapDepth) - separating
            if (wanted > 0.0) impulse += wanted / inverseSum
        }
        impulse = min(impulse, min(cap(a.aircraft) * mA, cap(b.aircraft) * mB))
        val deltaA = if (impulse > 0.0) n.scale(impulse / mA) else Vec3.ZERO
        val deltaB = if (impulse > 0.0) n.scale(-impulse / mB) else Vec3.ZERO

        val speed = max(closing, 0.0) * 20.0 - HARMLESS_SPEED_MPS
        if (speed <= 0.0) return Outcome(deltaA, deltaB, closing, 0.0, 0.0, false, false, false, false)
        val reduced = mA * mB / (mA + mB)
        val energy = 0.5 * reduced * speed * speed * (1.0 - RESTITUTION * RESTITUTION)
        val rawA = energy * (mB / (mA + mB)) / (toughness(a) * mA)
        val rawB = energy * (mA / (mA + mB)) / (toughness(b) * mB)
        val capA = heavyCap(mA, mB)
        val capB = heavyCap(mB, mA)
        val (damageA, lethalA, shearA) = zoneDamage(a, min(rawA, capA))
        val (damageB, lethalB, shearB) = zoneDamage(b, min(rawB, capB))
        return Outcome(deltaA, deltaB, closing, damageA, damageB, lethalA && capA.isInfinite(),
            lethalB && capB.isInfinite(), shearA && capA.isInfinite(), shearB && capB.isInfinite(),
            capA.isFinite(), capB.isFinite())
    }

    /** Damage cap for a vehicle of [own] tonnes hit by one of [other] tonnes. */
    fun heavyCap(own: Double, other: Double): Double {
        val ratio = other / own
        if (ratio >= 0.5) return Double.POSITIVE_INFINITY
        val t = ((ratio - 0.1) / 0.4).coerceIn(0.0, 1.0)
        return HEAVY_CAP_MIN + (1.0 - HEAVY_CAP_MIN) * t
    }

    private data class ZoneDamage(val fraction: Double, val lethal: Boolean, val shear: Boolean)

    private fun zoneDamage(body: Body, raw: Double): ZoneDamage {
        if (raw <= 0.0 || !raw.isFinite()) return ZoneDamage(0.0, false, false)
        val zones = body.zones.ifEmpty { setOf(Zone.BODY) }
        return when {
            Zone.BODY in zones -> ZoneDamage(raw, raw >= 1.0, false)
            Zone.WING in zones -> {
                val hull = raw * WING_HULL_FACTOR
                ZoneDamage(hull, hull >= 1.0, body.aircraft && raw >= WING_SHEAR_FRACTION)
            }
            else -> ZoneDamage(min(raw * GEAR_FACTOR, GEAR_CAP), false, false)
        }
    }

    private fun toughness(body: Body) = if (body.aircraft) AIRCRAFT_TOUGHNESS else GROUND_TOUGHNESS

    private fun safeMass(mass: Double) = if (mass.isFinite() && mass > 0.0) mass.coerceAtLeast(0.05) else 1.0

    private fun cap(aircraft: Boolean) = if (aircraft) MAX_AIRCRAFT_DELTA else MAX_GROUND_DELTA
}
