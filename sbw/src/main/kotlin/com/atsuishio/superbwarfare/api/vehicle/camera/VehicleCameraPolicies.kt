package com.atsuishio.superbwarfare.api.vehicle.camera

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity

internal fun VehicleEntity.controlsPlayerLookAim(
    entity: Entity,
    seatIndex: Int,
    selectedWeaponIndex: Int,
    mode: VehicleAimMode,
): Boolean {
    if (mode != VehicleAimMode.PLAYER_LOOK_AIM) return false
    val profile = resolveVehicleAimProfile(seatIndex, selectedWeaponIndex) ?: return false
    val controller = when (profile.channel) {
        VehicleAimChannel.TURRET -> getNthEntity(turretControllerIndex)
        VehicleAimChannel.PASSENGER_WEAPON -> getNthEntity(passengerWeaponStationControllerIndex)
    }
    return controller === entity
}
