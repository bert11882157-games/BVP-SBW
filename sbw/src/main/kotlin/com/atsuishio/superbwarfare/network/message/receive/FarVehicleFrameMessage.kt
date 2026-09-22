package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSnapshot
import com.atsuishio.superbwarfare.client.FarVehicleClient
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import kotlinx.serialization.Serializable

/** One bounded part of a complete, dimension-scoped visual frame. */
@Serializable
data class FarVehicleFrameMessage(
    val session: String,
    val dimension: String,
    val sequence: Long,
    val serverTick: Long,
    val interval: Int,
    val range: Int,
    val partIndex: Int,
    val partCount: Int,
    val vehicles: List<FarVehicleSnapshot>,
    val terrainToken: String = "",
    val terrainRevision: Long = -1,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        FarVehicleClient.receive(this@FarVehicleFrameMessage)
    }
}
