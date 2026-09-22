package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.network.FixedWingPilotIntentTransport
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/** A normalized ray, held switches, and optional screen-right roll demand, never aircraft state. */
@Serializable
data class FixedWingPilotIntentMessage @JvmOverloads constructor(
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val controlEpoch: Long,
    val sequence: Long,
    val worldX: Double,
    val worldY: Double,
    val worldZ: Double,
    val manualMask: Byte,
    val centerAim: Boolean,
    val screenRollInput: Float? = null,
    val firstPerson: Boolean = false,
    val inversionRequested: Boolean = false,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        FixedWingPilotIntentTransport.admit(sender(), this@FixedWingPilotIntentMessage)
    }
}
