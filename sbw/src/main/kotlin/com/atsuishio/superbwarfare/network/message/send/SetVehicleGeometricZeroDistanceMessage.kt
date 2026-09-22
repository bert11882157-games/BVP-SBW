package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.network.VehicleGeometricZeroDistanceTransport
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/**
 * Authenticated one-shot geometric-zero edge.  A non-null distance is the legacy manual
 * selection; null is the HasFCS G acquisition discriminator.  The client supplies no aim,
 * range, point, direction, or other authority in either form.
 */
@Serializable
data class SetVehicleGeometricZeroDistanceMessage(
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val requestedDistanceBlocks: Int?,
    val requestSequence: Long,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        VehicleGeometricZeroDistanceTransport.admit(sender(), this@SetVehicleGeometricZeroDistanceMessage)
    }
}
