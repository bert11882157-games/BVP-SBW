package com.atsuishio.superbwarfare.api.vehicle.camera

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode
import net.minecraft.world.phys.Vec3

/**
 * One immutable seat view resolved from a single vehicle pose. Body, eye, and direction anchors
 * are deliberately independent so suspension and movable stations cannot drift apart.
 */
class VehicleSeatPoseSnapshot(
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val bodyAnchor: String,
    val eyeAnchor: String,
    val directionAnchor: String,
    val bodyPosition: Vec3,
    val eyePosition: Vec3,
    val direction: Vec3?,
    val defaultCameraMode: VehicleCameraMode,
    val aimCameraMode: VehicleCameraMode,
) {
    init {
        require(seatIndex >= 0) { "seatIndex must be non-negative" }
        require(bodyAnchor.isNotBlank()) { "bodyAnchor cannot be blank" }
        require(eyeAnchor.isNotBlank()) { "eyeAnchor cannot be blank" }
        require(directionAnchor.isNotBlank()) { "directionAnchor cannot be blank" }
        require(bodyPosition.hasFiniteComponents()) { "bodyPosition must be finite" }
        require(eyePosition.hasFiniteComponents()) { "eyePosition must be finite" }
        require(direction == null || direction.hasFiniteComponents()) { "direction must be finite" }
    }

    fun cameraMode(aimMode: VehicleAimMode): VehicleCameraMode =
        if (aimMode == VehicleAimMode.PLAYER_LOOK_AIM) aimCameraMode else defaultCameraMode

}

private fun Vec3.hasFiniteComponents(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
