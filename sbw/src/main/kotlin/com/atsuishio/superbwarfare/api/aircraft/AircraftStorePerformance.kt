package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.item.gun.resolvedProfileId
import com.atsuishio.superbwarfare.tools.blast.ExternalMunitionBlasts
import com.atsuishio.superbwarfare.tools.blast.TntEquivalents
import com.google.gson.JsonObject
import net.minecraft.resources.ResourceLocation

/**
 * Nominal flight and warhead figures of an aircraft store, as the loadout editor shows them.
 *
 * The figures come from the same authored inputs the launch paths read: the store's `Flight` block
 * (FFA missiles), its `Bomb` block, or the gun and projectile profiles it launches through. This is
 * presentation only; it never changes how a store flies. Speeds are blocks per tick and
 * accelerations blocks per tick per tick; one block is one metre and a second is 20 ticks.
 */
object AircraftStorePerformance {
    const val TICKS_PER_SECOND = 20.0

    /** A figure, or the reason the store has none. Exactly one of [value] and [reason] is set. */
    data class Figure(val value: Double?, val reason: String?) {
        companion object {
            fun of(value: Double) = Figure(value, null)
            fun none(reason: String) = Figure(null, reason)
        }
    }

    data class Stats(
        val turnRateDegreesPerSecond: Figure,
        /** TNT-equivalent charge of everything one store releases, in kg. */
        val explosiveKgTnt: Figure,
        val accelerationBlocksPerTickSquared: Figure,
        val topSpeedBlocksPerTick: Figure,
    )

    /** The part of an SBW gun profile these figures read. */
    data class GunFigures(val velocityBlocksPerTick: Double, val tntEquivalentKg: Double,
                          val projectileProfile: String?)

    /** The part of a guided propulsion profile these figures read. */
    data class PropulsionFigures(val launchSpeedBlocksPerTick: Double, val maxSpeedBlocksPerTick: Double,
                                 val thrustDurationTicks: Int, val maxTurnRateDegreesPerSecond: Double)

    /** Profile lookups by resource id; null when the id is unknown on this side. */
    interface Sources {
        fun gun(id: String): GunFigures?
        fun propulsion(profileId: String): PropulsionFigures?
    }

    /** Loaded datapack profiles; both are synced, so this answers on either logical side. */
    object LoadedProfiles : Sources {
        override fun gun(id: String): GunFigures? = CustomData.GUN_DATA[id]?.let {
            GunFigures(it.velocity, it.tntEquivalentKg, it.projectile.value.resolvedProfileId()?.toString())
        }
        override fun propulsion(profileId: String): PropulsionFigures? =
            ProjectileProfiles.resolve(ResourceLocation.tryParse(profileId))?.guidedPropulsion
                ?.takeIf { it.isValid() }?.let {
                    PropulsionFigures(it.launchSpeed(), it.maxSpeed, it.thrustDurationTicks, it.maxTurnRateDegreesPerSecond)
                }
    }

    const val UNGUIDED = "Unguided"
    const val UNPOWERED = "Unpowered"
    const val FREE_FALL = "Free fall"
    const val NO_CHARGE = "None"
    const val FFA_DEFAULT = "Ballistics mod default"
    const val NOT_AUTHORED = "Not authored"

    /**
     * Figures for [store], or null for a store that releases nothing itself: a targeting pod, a
     * visual-only shape, or a launcher whose missiles are the aircraft's native weapon.
     */
    fun of(store: JsonObject, sources: Sources): Stats? {
        val category = store["Category"]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val flight = store.getAsJsonObject("Flight")
        return when {
            category in setOf("VISUAL_ONLY", AircraftTargetingPods.CATEGORY, AircraftMissileLaunchers.CATEGORY) -> null
            category == "BOMB" -> bomb(store)
            flight != null -> Stats(
                positive(flight, "TurnDegreesPerSecond"),
                charge(ExternalMunitionBlasts.storeCharge(store)),
                positive(flight, "AccelerationPerTick"),
                positive(flight, "MaxSpeed"))
            // FFA flies a guided store without a Flight block on its own defaults.
            store.has("Guidance") -> Stats(Figure.none(FFA_DEFAULT), charge(ExternalMunitionBlasts.storeCharge(store)),
                Figure.none(FFA_DEFAULT), Figure.none(FFA_DEFAULT))
            category == "AIR_TO_AIR" -> null
            else -> launched(store, category, sources)
        }
    }

    /** Average motor acceleration: the derived burn reaches its maximum after the authored burn. */
    fun burnAcceleration(propulsion: PropulsionFigures): Double =
        (propulsion.maxSpeedBlocksPerTick - propulsion.launchSpeedBlocksPerTick).coerceAtLeast(0.0) /
            propulsion.thrustDurationTicks.coerceAtLeast(1)

    private fun bomb(store: JsonObject): Stats? {
        val bomb = store.getAsJsonObject("Bomb") ?: return null
        val turnPerTick = number(bomb, "TurnDegreesPerTick") ?: 0.0
        val cluster = bomb.getAsJsonObject("Cluster")
        val kg = if (cluster != null) {
            TntEquivalents.sanitize(number(cluster, AircraftClusterBomb.BOMBLET_TNT_JSON_KEY) ?: 0.0) *
                (number(cluster, "Count") ?: 0.0)
        } else TntEquivalents.sanitize(number(bomb, "TntEquivalentKg") ?: 0.0)
        return Stats(
            if (turnPerTick > 0.0) Figure.of(turnPerTick * TICKS_PER_SECOND) else Figure.none(UNGUIDED),
            charge(kg), Figure.none(UNPOWERED), Figure.none(FREE_FALL))
    }

    /** Laser and command guided missiles, rocket pods and gun pods fire through an SBW gun profile. */
    private fun launched(store: JsonObject, category: String, sources: Sources): Stats {
        val gunId = (if (category in setOf("GUN_POD", "ROCKET_POD")) text(store, "GunProfile") else null)
            ?: text(store, "LaunchGunProfile")
        val gun = gunId?.let(sources::gun)
        val profileId = text(store, "ProjectileProfile") ?: gun?.projectileProfile
        val propulsion = profileId?.let(sources::propulsion)
        val kg = gun?.tntEquivalentKg?.takeIf { it > 0.0 } ?: (number(store, "TntEquivalentKg") ?: 0.0)
        val guided = category in setOf("LASER_GUIDED", "COMMAND_GUIDED")
        if (propulsion != null) return Stats(
            if (guided) Figure.of(propulsion.maxTurnRateDegreesPerSecond) else Figure.none(UNGUIDED),
            charge(TntEquivalents.sanitize(kg)),
            Figure.of(burnAcceleration(propulsion)),
            Figure.of(propulsion.maxSpeedBlocksPerTick))
        val missing = if (gun == null) NOT_AUTHORED else UNPOWERED
        return Stats(
            if (guided) Figure.none(NOT_AUTHORED) else Figure.none(UNGUIDED),
            charge(TntEquivalents.sanitize(kg)),
            Figure.none(missing),
            gun?.velocityBlocksPerTick?.takeIf { it.isFinite() && it > 0.0 }?.let(Figure::of)
                ?: Figure.none(NOT_AUTHORED))
    }

    private fun charge(kg: Double) = if (kg > 0.0) Figure.of(kg) else Figure.none(NO_CHARGE)
    private fun positive(json: JsonObject, key: String) =
        number(json, key)?.takeIf { it > 0.0 }?.let(Figure::of) ?: Figure.none(NOT_AUTHORED)
    private fun number(json: JsonObject, key: String): Double? = json[key]
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }
    private fun text(json: JsonObject, key: String): String? = json[key]
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
