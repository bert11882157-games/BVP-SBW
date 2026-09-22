package com.atsuishio.superbwarfare.api.vehicle.aim

/** Optional per-vehicle styling for the native synchronized weapon-aim marker. */
data class VehicleAimReticleProfile(
    val transitColor: Int,
    val lockedColor: Int,
    val centerSnapPixels: Double,
    val clampMarginPixels: Int,
    val armLengthPixels: Int,
    val gapPixels: Int,
) {
    init {
        require(centerSnapPixels.isFinite() && centerSnapPixels >= 0.0)
        require(clampMarginPixels >= 0)
        require(armLengthPixels > 0)
        require(gapPixels >= 0 && gapPixels < armLengthPixels)
    }
}

/** Implemented by add-on vehicles that want the native HUD to draw an aim-state marker. */
interface VehicleAimReticleProfileProvider {
    fun getVehicleAimReticleProfile(
        seatIndex: Int,
        selectedWeaponIndex: Int,
    ): VehicleAimReticleProfile?

    fun getVehicleAimReticleRole(
        seatIndex: Int,
        selectedWeaponIndex: Int,
    ): VehicleAimReticleRole
}

enum class VehicleAimOpticalCameraPolicy {
    WEAPON_ATTACHMENT,
    SEAT_AUTHORED,
    FOV_ONLY,
}

/** Stable selected-weapon presentation role; never inferred from translated display text. */
enum class VehicleAimReticleRole(
    val showsCameraCommandReticle: Boolean,
    val opticalCameraPolicy: VehicleAimOpticalCameraPolicy,
) {
    // Main optics magnify from the ordinary authored seat eye; aiming never moves the camera.
    MAIN_CANNON(true, VehicleAimOpticalCameraPolicy.FOV_ONLY),
    COAX(false, VehicleAimOpticalCameraPolicy.FOV_ONLY),
    PASSENGER_HMG(false, VehicleAimOpticalCameraPolicy.SEAT_AUTHORED),
    EMPLACEMENT_OPTIC(true, VehicleAimOpticalCameraPolicy.SEAT_AUTHORED),
    OTHER(true, VehicleAimOpticalCameraPolicy.WEAPON_ATTACHMENT),
}
