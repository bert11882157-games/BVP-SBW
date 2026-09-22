package com.atsuishio.superbwarfare.api.vehicle.camera

import kotlinx.serialization.Serializable

/**
 * First-person vehicle camera policy. The mode selects rotation ownership; position still comes
 * from the resolved seat eye anchor.
 */
@Serializable
enum class VehicleCameraMode {
    /** The rider's look rotation is independent and may drive an authoritative weapon servo. */
    PLAYER_LOOK_AIM,

    /** Both camera position and direction are resolved from the authored attachment graph. */
    FIXED_ATTACHMENT,

    /** SBW aircraft free-look offsets are composed with the live vehicle pose. */
    AIRCRAFT_FREELOOK,
}
