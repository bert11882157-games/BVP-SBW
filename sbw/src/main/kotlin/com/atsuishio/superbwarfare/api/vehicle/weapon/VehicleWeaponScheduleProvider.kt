package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.LivingEntity

data class VehicleWeaponSelection(
    val vehicle: VehicleEntity,
    val controller: LivingEntity,
    val seatIndex: Int,
    val weaponIndex: Int,
    val weaponName: String,
    val gunData: GunData,
)

interface VehicleWeaponScheduleProvider {
    fun resolve(selection: VehicleWeaponSelection): VehicleWeaponScheduleProfile?

    fun onAcceptedShot(
        selection: VehicleWeaponSelection,
        result: ShotResult,
        snapshot: VehicleWeaponScheduleSnapshot,
        scheduledSoundDue: Boolean,
        scheduledSoundPitch: Float,
    ) {
    }
}
