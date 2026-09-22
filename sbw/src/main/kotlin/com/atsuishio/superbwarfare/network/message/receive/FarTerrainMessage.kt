package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.FarTerrainClient
import com.atsuishio.superbwarfare.api.vehicle.render.FarTerrainPolicy
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
data class FarTerrainPlan(val token: String, val dimension: String, val revision: Long, val radius: Int,
                          val chunks: List<Long>, val invalidated: List<Long>) : ClientPacketPayload() {
    override fun PayloadContext.handler() { FarTerrainClient.plan(this@FarTerrainPlan) }
}

@Serializable
data class FarTerrainChunk(val token: String, val dimension: String, val revision: Long, val chunk: Long,
                           @Serializable(with = FarTerrainBytes::class) val data: ByteArray) : ClientPacketPayload() {
    override fun PayloadContext.handler() { FarTerrainClient.receive(this@FarTerrainChunk) }
}

object FarTerrainBytes : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("FarTerrainBytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) =
        (encoder as ByteBufEncoder).encodeBoundedByteArray(value, FarTerrainPolicy.MAX_CHUNK_BYTES)
    override fun deserialize(decoder: Decoder): ByteArray =
        (decoder as ByteBufDecoder).decodeBoundedByteArray(FarTerrainPolicy.MAX_CHUNK_BYTES)
}
