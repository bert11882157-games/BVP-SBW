@file:OptIn(ExperimentalSerializationApi::class)

package com.atsuishio.superbwarfare.serialization

import com.atsuishio.superbwarfare.network.PacketCodecLimits
import com.atsuishio.superbwarfare.network.PacketLimitProfiles
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import net.minecraft.network.FriendlyByteBuf

private val module = SerializersModule {}

class ByteBufEncoder(
    private val buf: FriendlyByteBuf,
    val packetLimits: PacketCodecLimits = PacketLimitProfiles.DATASET,
) : AbstractEncoder() {
    override val serializersModule = module

    override fun encodeBoolean(value: Boolean) {
        buf.writeBoolean(value)
    }

    override fun encodeByte(value: Byte) {
        buf.writeByte(value.toInt())
    }

    override fun encodeShort(value: Short) {
        buf.writeShort(value.toInt())
    }

    override fun encodeInt(value: Int) {
        buf.writeVarInt(value)
    }

    override fun encodeLong(value: Long) {
        buf.writeLong(value)
    }

    override fun encodeFloat(value: Float) {
        require(value.isFinite()) { "Non-finite float is not permitted on the SBW wire" }
        buf.writeFloat(value)
    }

    override fun encodeDouble(value: Double) {
        require(value.isFinite()) { "Non-finite double is not permitted on the SBW wire" }
        buf.writeDouble(value)
    }

    override fun encodeChar(value: Char) {
        buf.writeChar(value.code)
    }

    override fun encodeString(value: String) {
        require(value.length <= packetLimits.maxStringChars) {
            "String length ${value.length} exceeds ${packetLimits.maxStringChars} characters"
        }
        buf.writeUtf(value, packetLimits.maxStringChars)
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        buf.writeVarInt(index)
    }

    override fun encodeNull() {
        buf.writeBoolean(false)
    }

    override fun encodeNotNullMark() {
        buf.writeBoolean(true)
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int
    ): CompositeEncoder {
        require(collectionSize in 0..packetLimits.maxCollectionElements) {
            "Collection size $collectionSize exceeds ${packetLimits.maxCollectionElements} elements"
        }
        encodeInt(collectionSize)
        return this
    }

    fun encodeBoundedByteArray(value: ByteArray, maximumBytes: Int) {
        require(maximumBytes >= 0 && value.size <= maximumBytes) {
            "Byte array size ${value.size} exceeds $maximumBytes bytes"
        }
        buf.writeVarInt(value.size)
        buf.writeBytes(value)
    }
}

class ByteBufDecoder(
    private val buf: FriendlyByteBuf,
    val packetLimits: PacketCodecLimits = PacketLimitProfiles.DATASET,
    var elementIndex: Int = 0,
) : AbstractDecoder() {
    private var elementsCount = 0

    override val serializersModule = module

    override fun decodeBoolean() = buf.readBoolean()
    override fun decodeByte() = buf.readByte()
    override fun decodeShort() = buf.readShort()
    override fun decodeInt() = buf.readVarInt()
    override fun decodeLong() = buf.readLong()
    override fun decodeFloat() = buf.readFloat().also {
        if (!it.isFinite()) throw SerializationException("Non-finite float is not permitted on the SBW wire")
    }
    override fun decodeDouble() = buf.readDouble().also {
        if (!it.isFinite()) throw SerializationException("Non-finite double is not permitted on the SBW wire")
    }
    override fun decodeChar() = buf.readChar()
    override fun decodeString(): String = buf.readUtf(packetLimits.maxStringChars)
    override fun decodeEnum(enumDescriptor: SerialDescriptor) = decodeInt().also { index ->
        if (index !in 0 until enumDescriptor.elementsCount) {
            throw SerializationException("Enum index $index is outside ${enumDescriptor.serialName}")
        }
    }

    override fun decodeNotNullMark() = decodeBoolean()
    override fun decodeCollectionSize(descriptor: SerialDescriptor) = decodeInt().also { size ->
        if (size !in 0..packetLimits.maxCollectionElements) {
            throw SerializationException(
                "Collection size $size exceeds ${packetLimits.maxCollectionElements} elements for ${descriptor.serialName}",
            )
        }
        elementsCount = size
    }

    override fun beginStructure(descriptor: SerialDescriptor) =
        ByteBufDecoder(buf, packetLimits, descriptor.elementsCount)

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        if (elementIndex == elementsCount) return CompositeDecoder.DECODE_DONE
        return elementIndex++
    }

    override fun decodeSequentially() = true

    fun decodeBoundedByteArray(maximumBytes: Int): ByteArray {
        val size = buf.readVarInt()
        if (maximumBytes < 0 || size !in 0..maximumBytes) {
            throw SerializationException("Byte array size $size exceeds $maximumBytes bytes")
        }
        if (size > buf.readableBytes()) {
            throw SerializationException("Declared byte array size $size exceeds the remaining packet bytes")
        }
        return ByteArray(size).also { bytes -> buf.readBytes(bytes) }
    }
}
