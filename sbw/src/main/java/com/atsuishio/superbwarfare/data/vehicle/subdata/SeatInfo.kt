package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.annotation.ServerOnly
import com.atsuishio.superbwarfare.data.ObjectToList
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

@Serializable
class SeatInfo {
    @SerialName("HidePassenger")
    var hidePassenger: Boolean = false

    @SerialName("IsEnclosed")
    @ServerOnly
    var isEnclosed: Boolean? = null

    /** Open operators take their own incoming damage instead of sharing it with the vehicle. */
    @SerialName("ExposedToFire")
    @ServerOnly
    var exposedToFire: Boolean = false

    @JvmField
    @SerialName("Transform")
    var transform: String = "Default"

    @JvmField
    @SerialName("Pose")
    var pose: String = "Default"

    /** Optional client-only standing grip pose; body placement remains the seat's responsibility. */
    @SerialName("OperatorPose")
    var operatorPose: VehicleOperatorPoseInfo? = null

    @SerialName("Position")
    var position: SerializedVec3 = Vec3.ZERO

    /** Optional named attachment used as the authoritative rider-body frame. */
    @SerialName("BodyAttachment")
    var bodyAttachment: String? = null

    @SerialName("Orientation")
    var orientation: Float = 0f

    @SerialName("CanRotateBody")
    var canRotateBody: Boolean = false

    @SerialName("CanRotateHead")
    var canRotateHead: Boolean = true

    @JvmField
    @SerialName("HasThermalImaging")
    var hasThermalImaging: Boolean = false

    @SerialName("MinPitch")
    var minPitch: Float = -90f

    @SerialName("MaxPitch")
    var maxPitch: Float = 90f

    @SerialName("MinYaw")
    var minYaw: Float = -514f

    @SerialName("MaxYaw")
    var maxYaw: Float = 514f

    @SerialName("Weapons")
    private var weapons: ObjectToList<String>? = ObjectToList()

    fun weapons() = weapons?.list ?: mutableListOf()

    /** Exact fixed-gun channels fired together by PRIMARY; empty preserves native selection. */
    @ServerOnly
    @SerialName("FixedGunBank")
    var fixedGunBank: List<String> = emptyList()

    @SerialName("CameraPos")
    var cameraPos: CameraPos? = null

    @SerialName("BanHand")
    var banHand: Boolean = false

    @SerialName("Sensitivity")
    var sensitivity: SerializedVec3 = Vec3(1.0, 1.0, 1.0)

    @SerialName("DismountInfo")
    var dismountInfo: DismountInfo? = null
}
