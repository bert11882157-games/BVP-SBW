package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

/** A data-authored local frame parented to either a native vehicle frame or another attachment. */
@Serializable
class VehicleAttachmentInfo {
    @SerialName("Parent")
    var parent: String = "Vehicle"

    @SerialName("Position")
    var position: SerializedVec3 = Vec3.ZERO

    /** Local forward direction. The resolved attachment frame maps +Z onto this vector. */
    @SerialName("Direction")
    var direction: SerializedVec3 = Vec3(0.0, 0.0, 1.0)
}
