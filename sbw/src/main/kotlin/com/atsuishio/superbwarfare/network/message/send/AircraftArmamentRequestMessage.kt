package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.network.*
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

@Serializable
data class AircraftArmamentRequestMessage(val vehicle: SerializedUUID, val dimension: SerializedResourceLocation,
    val epoch: Long, val sequence: Long, val operation: String, val body: String) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        if (operation.length > 24 || sequence <= 0) return
        val json = AircraftArmamentNetwork.parseRequest(body) ?: return
        AircraftArmamentManager.handle(sender(), this@AircraftArmamentRequestMessage, json)
    }
}
