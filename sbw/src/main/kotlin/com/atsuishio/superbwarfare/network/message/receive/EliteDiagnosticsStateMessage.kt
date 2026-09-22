package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

@Serializable
data class EliteDiagnosticsStateMessage(val session: SerializedUUID, val enabled: Boolean) : ClientPacketPayload() {
    override fun PayloadContext.handler() { EliteDiagnostics.setClientSession(session, enabled) }
}
