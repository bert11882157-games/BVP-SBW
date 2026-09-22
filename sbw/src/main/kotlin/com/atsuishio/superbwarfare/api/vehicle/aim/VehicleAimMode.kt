package com.atsuishio.superbwarfare.api.vehicle.aim

import kotlinx.serialization.Serializable

/** Server-accepted weapon-control mode for one seat and selected weapon. */
@Serializable
enum class VehicleAimMode {
    INACTIVE,
    PLAYER_LOOK_AIM,
}
