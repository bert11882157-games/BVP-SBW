package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.weapon.FiredVisualProviders
import com.atsuishio.superbwarfare.api.weapon.FiredVisualRecord
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import kotlinx.serialization.Serializable

@Serializable
data class FiredVisualMessage(val record: FiredVisualRecord) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        FiredVisualProviders.dispatch(record)
    }
}
