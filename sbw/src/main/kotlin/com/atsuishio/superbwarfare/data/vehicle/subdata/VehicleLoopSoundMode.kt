package com.atsuishio.superbwarfare.data.vehicle.subdata

import kotlinx.serialization.Serializable

/** Selects who owns one vehicle loop-sound channel. */
@Serializable
enum class VehicleLoopSoundMode {
    NATIVE,
    CUSTOM,
    OFF
}

/** The independently routed loop-sound channels. */
enum class VehicleLoopSoundChannel {
    ENGINE,
    TRACK
}
