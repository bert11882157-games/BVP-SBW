package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.resources.ResourceLocation
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType

/** Explicit projectile extension; ordinary shells retain their existing explosion resolver. */
data class GroundVehicleBlastPolicy(val innerRadius: Double, val outerRadius: Double, val edgeDamage: Float) {
    fun appliesTo(type: VehicleType?): Boolean = type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER
    fun lethal(distance: Double): Boolean = distance.isFinite() && distance >= 0 && distance <= innerRadius
    fun damage(distance: Double): Float = if (!distance.isFinite() || distance < 0 || distance >= outerRadius) 0f
        else (edgeDamage * ((outerRadius - distance) / (outerRadius - innerRadius)).coerceIn(0.0, 1.0)).toFloat()

    companion object {
        val ID = ResourceLocation("superbwarfare", "ground_vehicle_blast_v1")
        fun from(profile: ResolvedProjectileProfile?): GroundVehicleBlastPolicy? =
            runCatching { parse(profile) }.getOrNull()

        /** Same parser as normal admission; diagnostics can retain the original failure. */
        fun parse(profile: ResolvedProjectileProfile?): GroundVehicleBlastPolicy? {
            val json = profile?.extension(ID)?.asJsonObject ?: return null
            val inner = json["InnerRadius"].asDouble
            val outer = json["OuterRadius"].asDouble
            val damage = json["DamageAtInnerEdge"].asFloat
            require(inner.isFinite() && outer.isFinite() && damage.isFinite())
            require(inner > 0 && inner < outer && outer <= 32 && damage > 0 && damage <= 100000)
            return GroundVehicleBlastPolicy(inner, outer, damage)
        }
    }
}
