package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.renderer.FriendlyVehicleMarker
import com.atsuishio.superbwarfare.network.*
import kotlinx.serialization.Serializable

@Serializable
data class FriendlyVehicleStateMessage(val dimension: String, val vehicles: List<String>) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (dimension.length > 256 || vehicles.size > 256 || vehicles.any { it.length != 36 }) return
        FriendlyVehicleMarker.receive(dimension, vehicles)
    }
}
