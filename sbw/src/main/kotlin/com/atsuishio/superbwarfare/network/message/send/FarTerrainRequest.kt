package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainServer
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import kotlinx.serialization.Serializable

/** Presentation priorities only; the server still chooses membership and all terrain chunks. */
@Serializable
data class FarTerrainRequest(val token: String, val dimension: String, val viewChunks: Int, val enabled: Boolean,
                             val visibleVehicles: List<String> = emptyList(),
                             val cameraOffsetX: Double = 0.0, val cameraOffsetZ: Double = 0.0) : ServerPacketPayload() {
    override fun PayloadContext.handler() { FarTerrainServer.request(sender(), this@FarTerrainRequest) }
}

@Serializable
data class FarTerrainAck(val token: String, val dimension: String, val revision: Long,
                         val chunks: List<Long> = emptyList()) : ServerPacketPayload() {
    override fun PayloadContext.handler() { FarTerrainServer.ack(sender(), this@FarTerrainAck) }
}
