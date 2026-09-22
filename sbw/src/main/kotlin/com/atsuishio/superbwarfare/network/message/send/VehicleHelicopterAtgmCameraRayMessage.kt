package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayTransport
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVector3f
import kotlinx.serialization.Serializable

/**
 * Latest final-camera sample for an airborne ATGM-capable operator seat. It contains no weapon
 * selection claim, hit point, range, ballistic result or correction; the server revalidates the
 * mounted operator/vehicle/seat stream and the immutable launch context owns weapon identity.
 */
@Serializable
data class VehicleHelicopterAtgmCameraRayMessage(
    val playerUuid: SerializedUUID,
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val seatIndex: Int,
    val contextEpoch: Long,
    val sequence: Long,
    val clientTick: Long,
    val origin: SerializedVector3f,
    val direction: SerializedVector3f,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        VehicleHelicopterAtgmCameraRayTransport.admit(sender(), this@VehicleHelicopterAtgmCameraRayMessage)
    }
}
