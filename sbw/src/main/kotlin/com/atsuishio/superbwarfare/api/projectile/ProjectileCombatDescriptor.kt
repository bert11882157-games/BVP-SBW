package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.projectile.PenetrationCurve
import com.atsuishio.superbwarfare.data.projectile.RicochetCurve
import net.minecraft.resources.ResourceLocation

/** Immutable, armor-facing projectile metadata. */
data class ProjectileCombatDescriptor(
    val profileId: ResourceLocation,
    val weaponId: ResourceLocation?,
    val roundId: ResourceLocation?,
    val munitionType: ResourceLocation?,
    val damageType: ResourceLocation?,
    val caliberMm: Double?,
    val penetrationMm: Double?,
    val tandem: Boolean,
    val penetrationCurve: PenetrationCurve? = null,
    val ricochetCurve: RicochetCurve? = null,
    val diameterMm: Double? = null,
    /** Required generation-time direct-hull classification. */
    val hullDamageClass: ProjectileHullDamageClass,
    /** Required generator-precomputed authoritative direct hull damage. */
    val hullDamage: Int,
    /** Precomputed ordinary-module damage for the exact authored projectile. */
    val moduleDamage: Int,
    /** Precomputed ammunition-rack damage for the exact authored projectile. */
    val ammoRackDamage: Int,
) {
    init {
        require(hullDamage >= 0) { "HullDamage must be non-negative" }
        require(moduleDamage >= 0) { "ModuleDamage must be non-negative" }
        require(ammoRackDamage >= 0) { "AmmoRackDamage must be non-negative" }
        require(
            (caliberMm?.isFinite() == true && caliberMm > 0.0) ||
                (diameterMm?.isFinite() == true && diameterMm > 0.0),
        ) { "ProjectileCombatDescriptor requires a positive caliber or diameter" }
    }

    /** True for every usable descriptor; construction enforces the complete tuple invariant. */
    fun hasPrecomputedDamage(): Boolean {
        return hullDamage >= 0 && moduleDamage >= 0 && ammoRackDamage >= 0
    }

    /**
     * Samples the bounded curve at the finite projectile travel range.  Invalid/nonfinite ranges
     * and malformed curves intentionally fall back to scalar Combat.PenetrationMm.
     */
    fun penetrationAtTravelRange(distanceMetres: Double): Double? {
        return penetrationCurve?.sample(distanceMetres) ?: penetrationMm
    }

    fun ricochetProbabilityAtIncidence(angleDegrees: Double): Double? {
        return ricochetCurve?.probabilityAtIncidence(angleDegrees)
    }
}
