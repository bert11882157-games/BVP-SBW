package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.network.PacketLimitProfiles
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainBytes
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainChunk
import com.atsuishio.superbwarfare.network.message.receive.FarTerrainPlan
import com.atsuishio.superbwarfare.network.message.send.FarTerrainAck
import com.atsuishio.superbwarfare.network.message.send.FarTerrainRequest
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import io.netty.buffer.Unpooled
import kotlinx.serialization.KSerializer
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FarTerrainCodecTest {
    private fun <T> roundtrip(serializer: KSerializer<T>, value: T,
                              limits: com.atsuishio.superbwarfare.network.PacketCodecLimits = PacketLimitProfiles.FAR_TERRAIN): T {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ByteBufEncoder(buffer, limits).encodeSerializableValue(serializer, value)
            val copy = ByteBufDecoder(buffer, limits).decodeSerializableValue(serializer)
            assertEquals(0, buffer.readableBytes())
            return copy
        } finally { buffer.release() }
    }

    @Test fun `real request plan chunk and acknowledgement codecs retain their scope`() {
        val token = "3c067621-1e52-47a7-b888-2ca3f35d86f2"
        val request = FarTerrainRequest(token, "minecraft:overworld", 12, true,
            List(256) { java.util.UUID(0L, it.toLong()).toString() }, 28.0, -15.0)
        assertEquals(request, roundtrip(FarTerrainRequest.serializer(), request, PacketLimitProfiles.CONTROL))
        val chunks = (0L until FarTerrainPolicy.MAX_CHUNKS.toLong()).toList()
        val plan = FarTerrainPlan(token, "minecraft:overworld", 9, FarTerrainPolicy.MAX_ACQUISITION_RADIUS,
            chunks, chunks)
        assertEquals(plan, roundtrip(FarTerrainPlan.serializer(), plan))
        val packet = FarTerrainChunk(token, "minecraft:overworld", 9, 22,
            ByteArray(FarTerrainPolicy.MAX_CHUNK_BYTES) { (it % 251).toByte() })
        val copy = roundtrip(FarTerrainChunk.serializer(), packet)
        assertEquals(packet.token, copy.token); assertEquals(packet.dimension, copy.dimension)
        assertEquals(packet.revision, copy.revision); assertEquals(packet.chunk, copy.chunk)
        assertArrayEquals(packet.data, copy.data)
        val ack = FarTerrainAck(token, "minecraft:overworld", 9, chunks)
        assertEquals(ack, roundtrip(FarTerrainAck.serializer(), ack, PacketLimitProfiles.FAR_TERRAIN_CONTROL))
    }

    @Test fun `terrain decoder rejects negative oversized and truncated byte arrays before allocation`() {
        for (size in listOf(-1, FarTerrainPolicy.MAX_CHUNK_BYTES + 1, 1024)) {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                buffer.writeVarInt(size)
                assertThrows(RuntimeException::class.java) {
                    FarTerrainBytes.deserialize(ByteBufDecoder(buffer, PacketLimitProfiles.FAR_TERRAIN))
                }
            } finally { buffer.release() }
        }
    }
}
