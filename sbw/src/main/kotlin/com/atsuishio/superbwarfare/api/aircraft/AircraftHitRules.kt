package com.atsuishio.superbwarfare.api.aircraft

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Aircraft damage rules (owner direction 2026-09-28, docs: tools/damage/REPORT.md). Aircraft hull HP scales with
 * size (400 x (full-fuel mass / 12 t)^(2/3), armoured attack types x1.25-1.35), and every weapon family deals:
 *
 *  - guns, per projectile by calibre: 7.62 mm 0.8, 12.7 2.5, 14.5 3.5, 20 14, 23 17, 25 20, 30 26, 37 36, 40 42,
 *    57 80 (a consolidated fast-firing aircraft round counts twice). A fighter takes a short-to-medium autocannon
 *    burst and a lot of .50 calibre before it goes down;
 *  - a tank shell (70 mm and up) destroys any aircraft;
 *  - a rocket or handheld RPG: 70 % of the aircraft's HP, at most 350 (crippling, not killing, for a fighter);
 *  - an anti-tank guided missile hitting the airframe: 90 % of HP, at most 600 (devastating to all but large ones);
 *  - air-to-air and surface-to-air missiles by charge ([missile]).
 */
object AircraftHitRules {
    private val GUN_MM = doubleArrayOf(7.62, 12.7, 14.5, 20.0, 23.0, 25.0, 30.0, 37.0, 40.0, 57.0)
    private val GUN_HP = doubleArrayOf(0.8, 2.5, 3.5, 14.0, 17.0, 20.0, 26.0, 36.0, 42.0, 80.0)
    const val LETHAL_CALIBRE_MM = 70.0
    const val ROCKET_FRACTION = 0.70
    const val ROCKET_CAP = 350.0
    const val ATGM_FRACTION = 0.90
    const val ATGM_CAP = 600.0

    /** Charges below this (TNT kg) are small missiles (Sidewinder, R-73, MANPADS); larger ones kill fighters. */
    const val SMALL_MISSILE_MAX_KG = 8.0
    /** A small missile takes 55-75 % of an airframe's HP, but counts a large airframe as a 600 HP one. */
    const val SMALL_MISSILE_REFERENCE_HP = 600.0
    /** Proximity burst at the edge of the severe radius keeps this share of the direct-hit damage. */
    const val PROXIMITY_FLOOR = 0.60

    enum class Kind { GUN, TANK_SHELL, ROCKET, ATGM, MISSILE }

    /** Per-projectile gun damage, linear between the table calibres; null for an unusable calibre. */
    @JvmStatic
    fun gun(calibreMm: Double?): Double? {
        if (calibreMm == null || !calibreMm.isFinite() || calibreMm < 1.0) return null
        if (calibreMm <= GUN_MM[0]) return GUN_HP[0] * max(0.25, calibreMm / GUN_MM[0])
        for (i in 1 until GUN_MM.size) {
            if (calibreMm <= GUN_MM[i]) {
                val t = (calibreMm - GUN_MM[i - 1]) / (GUN_MM[i] - GUN_MM[i - 1])
                return GUN_HP[i - 1] + t * (GUN_HP[i] - GUN_HP[i - 1])
            }
        }
        // 57 mm up to the lethal calibre: 80 -> 150
        return GUN_HP.last() + (calibreMm - GUN_MM.last()) / (LETHAL_CALIBRE_MM - GUN_MM.last()) * 70.0
    }

    @JvmStatic
    fun rocket(targetMaxHealth: Double): Double = min(ROCKET_FRACTION * targetMaxHealth, ROCKET_CAP)

    @JvmStatic
    fun atgm(targetMaxHealth: Double): Double = min(ATGM_FRACTION * targetMaxHealth, ATGM_CAP)

    /**
     * Air-to-air / surface-to-air missile of [kg] TNT equivalent: small ones 55 % (0.5 kg) to 75 % (8 kg) of the
     * airframe's HP (never more than 75 % of [SMALL_MISSILE_REFERENCE_HP]); large ones 1000 x (kg / 20)^0.3
     * (AIM-120 ~790, R-27 ~1060), which destroys any fighter. [proximity] is 1 for a direct hit.
     */
    @JvmStatic
    @JvmOverloads
    fun missile(kg: Double, targetMaxHealth: Double, proximity: Double = 1.0): Double {
        val charge = if (kg.isFinite() && kg > 0.0) kg else 1.0
        val base = if (charge < SMALL_MISSILE_MAX_KG) {
            val share = 0.55 + 0.20 * ((charge - 0.5) / (SMALL_MISSILE_MAX_KG - 0.5)).coerceIn(0.0, 1.0)
            share * min(targetMaxHealth, SMALL_MISSILE_REFERENCE_HP)
        } else 1000.0 * (charge / 20.0).pow(0.3)
        return base * proximity.coerceIn(0.0, 1.0)
    }

    /** 1 at the airframe, falling to [PROXIMITY_FLOOR] at [severeRadius]; 0 beyond it. */
    @JvmStatic
    fun proximity(distance: Double, severeRadius: Double): Double {
        if (!distance.isFinite() || distance < 0.0 || !(severeRadius > 0.0) || distance > severeRadius) return 0.0
        return 1.0 - (1.0 - PROXIMITY_FLOOR) * (distance / severeRadius)
    }

    /** The rule family from a projectile's typed combat data (munition type path, hull class, calibre). */
    @JvmStatic
    fun kind(munition: String?, hullClass: String?, calibreMm: Double?): Kind {
        val m = munition ?: ""
        return when {
            m == "atgm" || m == "sam" -> if (hullClass == "ATGM" || hullClass == "HEAT") Kind.ATGM else Kind.MISSILE
            m == "rocket" || m == "cluster_bomblet" -> Kind.ROCKET
            m == "tank_shell" || m == "heat_fs" || m == "he" -> Kind.TANK_SHELL
            calibreMm != null && calibreMm >= LETHAL_CALIBRE_MM -> Kind.TANK_SHELL
            else -> Kind.GUN
        }
    }
}
