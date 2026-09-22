package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.network.*
import com.google.gson.JsonParser
import kotlinx.serialization.Serializable

@Serializable
data class AircraftArmamentStateMessage(val body: String) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (body.length > 65536) return
        runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()?.let(AircraftArmamentNetwork::receive)
    }
}
