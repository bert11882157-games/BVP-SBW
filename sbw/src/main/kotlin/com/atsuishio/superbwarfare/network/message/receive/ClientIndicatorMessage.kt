package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.overlay.CrossHairOverlay
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import kotlinx.serialization.Serializable

@Serializable
data class ClientIndicatorMessage(
    val type: Int,
    val value: Int,
) : ClientPacketPayload() {

    override fun PayloadContext.handler() {
        if (java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios")) {
            com.atsuishio.superbwarfare.Mod.LOGGER.info("Hit indicator received: type {} ({}) for {} ticks", type,
                when (type) { 1 -> "headshot"; 2 -> "kill"; 3 -> "vehicle"; else -> "hit" }, value)
        }
        when (type) {
            1 -> CrossHairOverlay.headIndicator = value
            2 -> CrossHairOverlay.killIndicator = value
            3 -> CrossHairOverlay.vehicleIndicator = value
            else -> CrossHairOverlay.hitIndicator = value
        }
    }
}
