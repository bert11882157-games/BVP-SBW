package com.atsuishio.superbwarfare.data.vehicle.subdata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Named physical grip targets for the opt-in MachineGunStand upper-body pose. */
@Serializable
class VehicleOperatorPoseInfo {
    @SerialName("LeftHandAttachment")
    var leftHandAttachment: String = ""

    @SerialName("RightHandAttachment")
    var rightHandAttachment: String = ""

    @SerialName("MaxForwardLeanDegrees")
    var maxForwardLeanDegrees: Float = 60f

    @SerialName("MaxBackwardLeanDegrees")
    var maxBackwardLeanDegrees: Float = 15f
}
