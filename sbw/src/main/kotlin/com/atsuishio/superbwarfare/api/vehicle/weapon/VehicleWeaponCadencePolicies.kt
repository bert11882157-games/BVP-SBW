package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily
import net.minecraft.resources.ResourceLocation

/** Values captured from the selected weapon; cadence providers cannot access live weapon state. */
data class VehicleWeaponCadenceInput(val family: ProjectileBeltFamily?, val roundId: ResourceLocation?)

fun interface VehicleWeaponCadencePolicy {
    /** Return a positive event rate, or null to leave this weapon's schedule unchanged. */
    fun eventRpm(input: VehicleWeaponCadenceInput): Int?
}

/** Addon rate constraints apply after profile selection without replacing its other policies. */
object VehicleWeaponCadencePolicies {
    private val policies = OrderedProviderRegistry<ResourceLocation, VehicleWeaponCadencePolicy>()
    @JvmStatic fun register(id: ResourceLocation, policy: VehicleWeaponCadencePolicy) = policies.register(id, policy)
    @JvmStatic fun unregister(id: ResourceLocation): Boolean = policies.unregister(id)
    @JvmStatic fun apply(input: VehicleWeaponCadenceInput, profile: VehicleWeaponScheduleProfile): VehicleWeaponScheduleProfile {
        for (policy in policies.snapshot().values) {
            val rate = policy.eventRpm(input) ?: continue
            require(rate > 0) { "Cadence policy must provide a positive event rate" }
            return profile.copy(eventRpm = rate,
                bulletRpm = (rate.toLong() * profile.projectilesPerEvent).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        return profile
    }
}
