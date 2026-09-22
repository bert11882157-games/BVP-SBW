package com.atsuishio.superbwarfare.api.vehicle.aim

/** Vehicle-specific values remain in addon profiles; SBW owns the generic servo lifecycle. */
fun interface VehicleAimProfileProvider {
    fun createVehicleAimProfile(seatIndex: Int, selectedWeaponIndex: Int): VehicleAimProfile?
}
