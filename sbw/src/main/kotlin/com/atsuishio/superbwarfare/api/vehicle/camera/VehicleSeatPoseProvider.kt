package com.atsuishio.superbwarfare.api.vehicle.camera

import net.minecraft.world.entity.Entity

/** Opt-in seat-pose authority. Returning null preserves the complete legacy SBW path. */
fun interface VehicleSeatPoseProvider {
    fun createVehicleSeatPose(
        passenger: Entity,
        seatIndex: Int,
        selectedWeaponIndex: Int,
        partialTicks: Float,
        zooming: Boolean,
    ): VehicleSeatPoseSnapshot?
}
