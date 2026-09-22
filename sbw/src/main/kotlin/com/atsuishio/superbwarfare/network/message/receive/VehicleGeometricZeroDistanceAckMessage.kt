package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.VehicleGeometricZeroDistanceClient
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.VehicleGeometricZeroWireStatus
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/**
 * Reliable controller-only ID67 result.  MANUAL carries the old selected distance; HasFCS
 * statuses carry only bounded scalar range/elevation and transform provenance.  No point or
 * direction is ever serialized.
 */
@Serializable
data class VehicleGeometricZeroDistanceAckMessage(
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val operatorUuid: SerializedUUID?,
    val seatIndex: Int,
    val selectedWeaponIndex: Int,
    val requestSequence: Long,
    val revision: Long,
    val contextEpoch: Long,
    val authoritativeServerTick: Long,
    val status: VehicleGeometricZeroWireStatus,
    val distanceBlocks: Int?,
    val measuredRangeBlocks: Double?,
    val elevationOffsetDegrees: Double?,
    val sourceTransformSequence: Int,
    val sourceTransformServerTick: Long,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        VehicleGeometricZeroDistanceClient.accept(this@VehicleGeometricZeroDistanceAckMessage)
    }
}
