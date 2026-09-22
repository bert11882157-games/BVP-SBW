package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.api.weapon.ShotFrameReference
import net.minecraft.world.phys.Vec3

/** Immutable resolution of the ballistic and presentation frame for one logical shot. */
data class VehicleMuzzleFrame(
    val weaponName: String,
    val position: Vec3,
    val direction: Vec3,
    val effectPosition: Vec3,
    val effectDirection: Vec3,
    val frameReference: ShotFrameReference,
)
