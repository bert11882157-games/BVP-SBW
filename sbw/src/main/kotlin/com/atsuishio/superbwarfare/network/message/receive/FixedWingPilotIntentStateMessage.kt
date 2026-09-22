package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.FixedWingPilotIntentClient
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.FixedWingPilotIntentWireValue
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/** Operator-only server lease; null value explicitly closes this epoch. */
@Serializable
data class FixedWingPilotIntentStateMessage(
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val operatorUuid: SerializedUUID,
    val controlEpoch: Long,
    val serverTick: Long,
    val active: Boolean,
    val value: FixedWingPilotIntentWireValue?,
) : ClientPacketPayload() {
    fun valid(): Boolean = vehicleId >= 0 && controlEpoch > 0L && serverTick >= 0L &&
        if (active) value != null && value.acceptedSequence >= -1L && value.intent() != null &&
            (value.acceptedSequence >= 0L || !value.inversionRequested)
        else value == null

    override fun PayloadContext.handler() {
        FixedWingPilotIntentClient.accept(this@FixedWingPilotIntentStateMessage)
    }
}
