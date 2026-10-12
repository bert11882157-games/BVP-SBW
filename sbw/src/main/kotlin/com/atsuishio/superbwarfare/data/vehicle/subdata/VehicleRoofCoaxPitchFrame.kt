package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed opt-in definition for a roof-mounted coax whose pitch axis is independent of the
 * ordinary [Barrel] frame. The pivot is local to the native [Turret] frame; the attachment
 * graph supplies the authored muzzle as a child of [RoofCoaxPitch].
 */
@Serializable
enum class VehicleRoofCoaxPitchParent {
    @SerialName("Turret")
    TURRET,
}

@Serializable
class VehicleRoofCoaxPitchFrame {
    @SerialName("Schema")
    var schema: String = SCHEMA

    @SerialName("Parent")
    var parent: VehicleRoofCoaxPitchParent? = VehicleRoofCoaxPitchParent.TURRET

    /** Roof pitch pivot in the authored Turret frame, before the actual pitch rotation. */
    @SerialName("Pivot")
    var pivot: SerializedVec3? = null

    /** Exact data-authored child consumed by muzzle position/direction/effect resolution. */
    @SerialName("MuzzleAttachment")
    var muzzleAttachment: String? = null

    fun hasTypedIdentity(): Boolean {
        val authoredPivot = pivot ?: return false
        return schema == SCHEMA && parent == VehicleRoofCoaxPitchParent.TURRET &&
            muzzleAttachment?.isNotBlank() == true &&
            authoredPivot.x.isFinite() && authoredPivot.y.isFinite() && authoredPivot.z.isFinite()
    }

    companion object {
        const val SCHEMA = "berts_vehicle_pack:roof_coax_pitch/v1"
        const val NATIVE_FRAME = "RoofCoaxPitch"
    }
}
