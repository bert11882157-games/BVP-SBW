package com.atsuishio.superbwarfare.api.vehicle.pose

/**
 * Opt-in server-authoritative pose owner for a vehicle.
 *
 * When no flight strategy is selected, SBW invokes this once after movement and immediately before
 * OBB resolution. Legacy terrain/inertia pose is suppressed unless [usesLegacyBasePose] opts into
 * server-side composition. Implementations may apply movement-side response before returning the
 * final immutable extension pose. A selected flight strategy has higher per-tick attitude
 * precedence. Clients consume only the synchronized result.
 */
fun interface VehiclePoseProvider {
    fun updateVehiclePose(previous: VehiclePoseSnapshot): VehiclePoseSnapshot

    /**
     * Opts this provider into SBW's native terrain/inertia attitude as the synchronized base layer.
     * The native solver remains server-only; the provider's pose is appended as an extension.
     */
    fun usesLegacyBasePose(): Boolean = false
}
