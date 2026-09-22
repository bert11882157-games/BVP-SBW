package com.atsuishio.superbwarfare.api.projectile

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import net.minecraft.resources.ResourceLocation

/** Authored gameplay damage, resolved once independently of native armor attenuation. */
data class HeavyWarheadBlastPolicy(val innerRadius: Double, val outerRadius: Double,
    val heavyFraction: Float, val lightFraction: Float) {
    init {
        require(innerRadius.isFinite() && outerRadius.isFinite() && innerRadius > 0 && outerRadius > innerRadius && outerRadius <= 32)
        require(heavyFraction.isFinite() && lightFraction.isFinite() && heavyFraction in 0f..1f && lightFraction in 0f..1f)
    }
    fun affects(distance: Double) = distance.isFinite() && distance >= 0 && distance <= outerRadius
    // An unspecified type never implies aircraft/APC; explicit light armor still selects its fraction.
    fun lethal(distance: Double, type: VehicleType?) = affects(distance) &&
        (distance <= innerRadius || type == VehicleType.AIRPLANE || type == VehicleType.HELICOPTER || type == VehicleType.DRONE)
    fun damage(distance: Double, type: VehicleType?, light: Boolean, health: Float, maximum: Float): Float {
        if (!affects(distance) || !health.isFinite() || !maximum.isFinite() || maximum <= 0) return 0f
        return if (lethal(distance, type)) maxOf(health, maximum)
            else maximum * if (light || type == VehicleType.APC) lightFraction else heavyFraction
    }
    companion object {
        val ID = ResourceLocation("superbwarfare", "heavy_warhead_blast_v1")
        fun from(profile: ResolvedProjectileProfile?): HeavyWarheadBlastPolicy? = runCatching {
            val j = profile?.extension(ID)?.asJsonObject ?: return null
            HeavyWarheadBlastPolicy(j["InnerRadius"].asDouble, j["OuterRadius"].asDouble,
                j["HeavyDamageFraction"].asFloat, j["LightDamageFraction"].asFloat)
        }.getOrNull()
    }
}
