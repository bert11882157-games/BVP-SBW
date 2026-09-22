package com.atsuishio.superbwarfare.serialization.kserializer

import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

typealias CompressedString = @Serializable(CompressedStringSerializer::class) String

object CompressedStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("CompressedString", PrimitiveKind.STRING)

    private fun compress(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { output ->
            output.write(data)
            output.finish()
        }
        return outputStream.toByteArray()
    }

    private fun decompress(compressedData: ByteArray, maximumBytes: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()

        GZIPInputStream(ByteArrayInputStream(compressedData)).use { input ->
            val buffer = ByteArray(1024)
            var len: Int
            while ((input.read(buffer).also { len = it }) != -1) {
                require(outputStream.size() <= maximumBytes - len) {
                    "Decompressed string exceeds $maximumBytes bytes"
                }
                outputStream.write(buffer, 0, len)
            }
        }
        return outputStream.toByteArray()
    }

    private val CACHE = CacheBuilder.newBuilder()
        .maximumSize(4)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build(object : CacheLoader<String, ByteArray>() {
            override fun load(str: String): ByteArray {
                return compress(str.toByteArray(StandardCharsets.UTF_8))
            }
        })

    override fun serialize(encoder: Encoder, value: String) {
        require(encoder is ByteBufEncoder) { "CompressedString requires the bounded SBW byte-buffer encoder" }
        val uncompressedBytes = value.toByteArray(StandardCharsets.UTF_8)
        require(uncompressedBytes.size <= encoder.packetLimits.maxDecompressedBytes) {
            "Uncompressed string size ${uncompressedBytes.size} exceeds ${encoder.packetLimits.maxDecompressedBytes} bytes"
        }
        val compressed = CACHE.getUnchecked(value)
        encoder.encodeBoundedByteArray(compressed, encoder.packetLimits.maxCompressedBytes)
    }

    override fun deserialize(decoder: Decoder): String {
        require(decoder is ByteBufDecoder) { "CompressedString requires the bounded SBW byte-buffer decoder" }
        val compressed = decoder.decodeBoundedByteArray(decoder.packetLimits.maxCompressedBytes)
        val decompressed = decompress(compressed, decoder.packetLimits.maxDecompressedBytes)
        return String(decompressed, StandardCharsets.UTF_8)
    }
}
