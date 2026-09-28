package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import java.util.WeakHashMap

/**
 * In-flight roll of a spun missile, from its projectile profile's `superbwarfare:missile_roll_v1` extension:
 *
 * ```
 * {"Schema": 1, "RollHz": 8.5, "RollAccelerationHzPerSecond": 17, "Direction": "CLOCKWISE"}
 * ```
 *
 * `RollHz` is the missile's real steady roll rate and `RollAccelerationHzPerSecond` its spin-up after launch; the
 * roll shown is capped at [MAX_HZ] (faster reads as stutter of the orbiting thrusters at 20-60 fps). `Direction` is
 * as seen from behind the missile (from the launcher). A roll-stabilised missile (TOW) has `RollHz` 0. The body roll
 * (BVP projectile renderer), the four orbiting thruster plumes (FFA `MissileExhaust`, BVP fallback trail) and the
 * plumes' growth all read this one curve, so body and plumes turn together. Sources: docs/ATGM_ROLL.md.
 */
object MissileRoll {
    /** The fastest roll drawn. */
    const val MAX_HZ = 6.0
    @JvmField val EXTENSION = ResourceLocation("superbwarfare", "missile_roll_v1")

    /** [hz] applied steady rate (capped), [acceleration] Hz/s, [sign] +1 counterclockwise / -1 clockwise from behind. */
    class Roll(@JvmField val hz: Double, @JvmField val acceleration: Double, @JvmField val sign: Double) {
        /** Seconds from launch to the applied steady rate. */
        val spinUp: Double get() = if (hz <= 0 || acceleration <= 0) 0.0 else hz / acceleration
        /** Roll rate (Hz, unsigned) [seconds] after launch. */
        fun rate(seconds: Double): Double = if (hz <= 0 || !(seconds > 0)) 0.0
            else if (acceleration <= 0) hz else minOf(hz, acceleration * seconds)
        /** Revolutions (signed: + counterclockwise seen from behind) [seconds] after launch. */
        fun turns(seconds: Double): Double {
            if (hz <= 0 || !(seconds > 0)) return 0.0
            if (acceleration <= 0) return sign * hz * seconds
            val t1 = hz / acceleration
            val unsigned = if (seconds <= t1) 0.5 * acceleration * seconds * seconds
                else 0.5 * hz * t1 + hz * (seconds - t1)
            return sign * unsigned
        }
        /** Roll rate as a fraction (0..1) of the applied steady rate. */
        fun fraction(seconds: Double): Double = if (hz <= 0) 0.0 else (rate(seconds) / hz).coerceIn(0.0, 1.0)
    }

    private val cache = WeakHashMap<ResolvedProjectileProfile, Roll>()
    private val NONE = Roll(-1.0, 0.0, 1.0)

    /** The missile's roll, or null when its profile declares none. */
    @JvmStatic fun of(entity: Entity): Roll? {
        val profile = runCatching { ProjectileProfiles.resolve(entity) }.getOrNull() ?: return null
        val roll = synchronized(cache) { cache.getOrPut(profile) { parse(profile) } }
        return roll.takeIf { it !== NONE }
    }

    private fun parse(profile: ResolvedProjectileProfile): Roll = runCatching {
        val json = profile.extension(EXTENSION)?.asJsonObject ?: return NONE
        val hz = json["RollHz"].asDouble
        val acceleration = json["RollAccelerationHzPerSecond"]?.asDouble ?: 0.0
        require(hz.isFinite() && hz in 0.0..100.0 && acceleration.isFinite() && acceleration in 0.0..1000.0)
        val sign = if (json["Direction"]?.asString == "COUNTERCLOCKWISE") 1.0 else -1.0
        Roll(minOf(hz, MAX_HZ), acceleration, sign)
    }.getOrDefault(NONE)

    /**
     * Presentation hook (also called by FFA through reflection): {signed turns, fraction of the steady rate} for the
     * missile [seconds] after launch, or null when it declares no roll.
     */
    @JvmStatic fun state(entity: Entity, seconds: Double): DoubleArray? =
        of(entity)?.let { doubleArrayOf(it.turns(seconds), it.fraction(seconds)) }

    /** Orbiting thruster plume scale (length and outward cant) for a roll fraction: 25% at launch, 150% at full roll. */
    @JvmStatic fun plumeScale(fraction: Double): Double = 0.25 + 1.25 * fraction.coerceIn(0.0, 1.0)
}
