package com.atsuishio.superbwarfare.data.projectile

import com.atsuishio.superbwarfare.data.IDBasedData
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedGsonObject
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass
import com.google.gson.JsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Synced, namespaced projectile metadata. The profile id is supplied by the
 * resource path (data/<namespace>/sbw/projectile_profiles/<path>.json).
 */
@Serializable
class DefaultProjectileProfile : IDBasedData<DefaultProjectileProfile> {
    @Transient
    @kotlinx.serialization.Transient
    private var profileId: String = ""

    override fun getId(): String = profileId

    override fun setId(id: String) {
        profileId = id
    }

    @SerialName("Combat")
    var combat: ProjectileCombatData? = null

    @SerialName("VisualProfile")
    var visualProfile: SerializedResourceLocation? = null

    @SerialName("ImpactVisualProfile")
    var impactVisualProfile: SerializedResourceLocation? = null

    @SerialName("MotionSync")
    var motionSync: MotionSyncPolicy = MotionSyncPolicy.INHERIT

    @SerialName("Collision")
    var collision: ProjectileCollisionData? = null

    @SerialName("Luminance")
    var luminance: Int = 0

    @SerialName("TrailMode")
    var trailMode: ProjectileTrailMode = ProjectileTrailMode.DEFAULT

    @SerialName("RenderScale")
    var renderScale: Float = 1f

    /**
     * Optional server-authoritative propulsion contract for guided missile profiles.  The
     * nullable raw object intentionally keeps an absent field as the legacy/no-acceleration
     * path; the runtime resolver rejects incomplete or out-of-bounds objects before publishing a
     * usable typed profile.
     */
    @SerialName("GuidedPropulsion")
    var guidedPropulsion: GuidedPropulsionData? = null

    @SerialName("Extensions")
    var extensions: SerializedGsonObject = JsonObject()
}

/** Raw datapack representation; nullable values allow the resolver to fail closed on bad data. */
@Serializable
data class GuidedPropulsionData(
    @SerialName("InitialSpeed")
    var initialSpeed: Double? = null,

    @SerialName("MaxSpeed")
    var maxSpeed: Double? = null,

    @SerialName("AccelerationPerTick")
    var accelerationPerTick: Double? = null,

    @SerialName("ThrustDurationTicks")
    var thrustDurationTicks: Int? = null,

    @SerialName("MaxTurnRateDegreesPerSecond")
    var maxTurnRateDegreesPerSecond: Double? = null,

    @SerialName("GuidanceLookAheadTicks")
    var guidanceLookAheadTicks: Int? = null,
)

@Serializable
data class ProjectileCombatData(
    @SerialName("WeaponId")
    var weaponId: SerializedResourceLocation? = null,

    @SerialName("RoundId")
    var roundId: SerializedResourceLocation? = null,

    @SerialName("MunitionType")
    var munitionType: SerializedResourceLocation? = null,

    @SerialName("DamageType")
    var damageType: SerializedResourceLocation? = null,

    @SerialName("CaliberMm")
    var caliberMm: Double? = null,

    /** Explicit projectile/body diameter for rockets and ATGMs; never inferred from visuals. */
    @SerialName("DiameterMm")
    var diameterMm: Double? = null,

    /** Required in a usable profile; the raw DTO stays nullable so the resolver can reject malformed input. */
    @SerialName("HullDamageClass")
    var hullDamageClass: ProjectileHullDamageClass? = null,

    /** Generator-precomputed direct hull damage; runtime never derives it from diameter. */
    @SerialName("HullDamage")
    var hullDamage: Int? = null,

    /** Generator-precomputed ordinary module damage. */
    @SerialName("ModuleDamage")
    var moduleDamage: Int? = null,

    /** Generator-precomputed ammunition-rack damage. */
    @SerialName("AmmoRackDamage")
    var ammoRackDamage: Int? = null,

    @SerialName("PenetrationMm")
    var penetrationMm: Double? = null,

    /** Optional distance-dependent WT-style penetration curve; scalar PenetrationMm remains the fallback. */
    @SerialName("PenetrationCurve")
    var penetrationCurve: PenetrationCurve? = null,

    /** Optional per-round WT ricochet probability curve sampled by natural incidence angle. */
    @SerialName("RicochetCurve")
    var ricochetCurve: RicochetCurve? = null,

    @SerialName("Tandem")
    var tandem: Boolean = false,
)

/**
 * Bounded, monotonically ordered penetration samples in metres and millimetres.  Curves are
 * advisory metadata for the authoritative impact consumer; malformed data deliberately falls
 * back to the existing scalar Combat.PenetrationMm value.
 */
@Serializable
data class PenetrationCurve(
    @SerialName("DistancesMetres")
    val distancesMetres: List<Double> = emptyList(),

    @SerialName("PenetrationMm")
    val penetrationMm: List<Double> = emptyList(),
) {
    fun isValid(): Boolean {
        if (distancesMetres.isEmpty() || distancesMetres.size != penetrationMm.size ||
            distancesMetres.size > MAX_SAMPLES
        ) return false
        var previousDistance = -1.0
        for (index in distancesMetres.indices) {
            val distance = distancesMetres[index]
            val penetration = penetrationMm[index]
            if (!distance.isFinite() || distance < 0.0 || distance <= previousDistance ||
                !penetration.isFinite() || penetration < 0.0
            ) return false
            previousDistance = distance
        }
        return true
    }

    /** Clamped piecewise-linear sample, or null when the curve/range is malformed. */
    fun sample(distanceMetres: Double): Double? {
        if (!isValid() || !distanceMetres.isFinite()) return null
        if (distanceMetres <= distancesMetres.first()) return penetrationMm.first()
        val last = distancesMetres.lastIndex
        if (distanceMetres >= distancesMetres[last]) return penetrationMm[last]
        for (index in 1..last) {
            val upperDistance = distancesMetres[index]
            if (distanceMetres > upperDistance) continue
            val lowerDistance = distancesMetres[index - 1]
            val span = upperDistance - lowerDistance
            val fraction = (distanceMetres - lowerDistance) / span
            return penetrationMm[index - 1] +
                (penetrationMm[index] - penetrationMm[index - 1]) * fraction
        }
        return null
    }

    companion object {
        private const val MAX_SAMPLES = 32
    }
}

/**
 * Bounded per-round ricochet probabilities.  Angles are incidence angles from the plate normal
 * (0 degrees is square-on and 90 degrees is grazing); probabilities are not inferred from
 * caliber or a shared gun default.  Malformed curves are intentionally ignored by the
 * authoritative consumer, which preserves the scalar penetration/non-ricochet fallback until
 * the data maintainer materializes a complete WT-derived round entry.
 */
@Serializable
data class RicochetCurve(
    @SerialName("IncidenceAnglesDegrees")
    val incidenceAnglesDegrees: List<Double> = emptyList(),

    @SerialName("Probability")
    val probability: List<Double> = emptyList(),
) {
    fun isValid(): Boolean {
        if (incidenceAnglesDegrees.isEmpty() ||
            incidenceAnglesDegrees.size != probability.size ||
            incidenceAnglesDegrees.size > MAX_SAMPLES
        ) return false
        var previous = -1.0
        for (index in incidenceAnglesDegrees.indices) {
            val angle = incidenceAnglesDegrees[index]
            val chance = probability[index]
            if (!angle.isFinite() || angle < 0.0 || angle > 90.0 || angle <= previous ||
                !chance.isFinite() || chance < 0.0 || chance > 1.0
            ) return false
            previous = angle
        }
        return true
    }

    /** Clamped piecewise-linear probability, or null for malformed curve/input. */
    fun probabilityAtIncidence(angleDegrees: Double): Double? {
        if (!isValid() || !angleDegrees.isFinite()) return null
        if (angleDegrees <= incidenceAnglesDegrees.first()) return probability.first()
        val last = incidenceAnglesDegrees.lastIndex
        if (angleDegrees >= incidenceAnglesDegrees[last]) return probability[last]
        for (index in 1..last) {
            val upperAngle = incidenceAnglesDegrees[index]
            if (angleDegrees > upperAngle) continue
            val lowerAngle = incidenceAnglesDegrees[index - 1]
            val fraction = (angleDegrees - lowerAngle) / (upperAngle - lowerAngle)
            return probability[index - 1] +
                (probability[index] - probability[index - 1]) * fraction
        }
        return null
    }

    companion object {
        private const val MAX_SAMPLES = 32
    }
}

@Serializable
data class ProjectileCollisionData(
    @SerialName("Width")
    var width: Float = 0f,

    @SerialName("Height")
    var height: Float = 0f,
)

@Serializable
enum class MotionSyncPolicy {
    INHERIT,
    NONE,
    ENTITY_INTERVAL,
    EVERY_TICK,
}

@Serializable
enum class ProjectileTrailMode {
    DEFAULT,
    REPLACE,
    SUPPRESS,
}
