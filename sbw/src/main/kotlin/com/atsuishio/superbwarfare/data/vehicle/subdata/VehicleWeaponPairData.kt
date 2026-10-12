package com.atsuishio.superbwarfare.data.vehicle.subdata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client-readable generated vehicle weapon-pair authoring. */
@Serializable
class VehicleWeaponPairData {
    @SerialName("Seat")
    var seat: Int = -1

    @SerialName("Primary")
    var primary: String = ""

    @SerialName("Secondary")
    var secondary: String? = null
}
