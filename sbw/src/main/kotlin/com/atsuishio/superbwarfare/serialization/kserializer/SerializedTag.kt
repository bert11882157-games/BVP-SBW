package com.atsuishio.superbwarfare.serialization.kserializer

import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import com.google.common.io.ByteStreams
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.Tag
import net.minecraft.nbt.TagTypes

typealias SerializedTag = @Serializable(TagSerializer::class) Tag

object TagSerializer : KSerializer<Tag> {
    override val descriptor = buildClassSerialDescriptor("Tag") {
        element<Byte>("type")
        element<ByteArray>("data")
    }

    private val byteArraySerializer = ByteArraySerializer()

    override fun serialize(encoder: Encoder, value: Tag) {
        val byteArray = ByteStreams.newDataOutput()
            .apply { value.write(this) }
            .toByteArray()
        encoder.encodeByte(value.id)
        if (encoder is ByteBufEncoder) {
            encoder.encodeBoundedByteArray(byteArray, encoder.packetLimits.maxNbtBytes)
        } else {
            encoder.encodeSerializableValue(byteArraySerializer, byteArray)
        }
    }

    override fun deserialize(decoder: Decoder): Tag {
        val type = decoder.decodeByte().toInt()
        require(type in MIN_TAG_TYPE..MAX_TAG_TYPE) { "Invalid NBT type id $type" }
        val byteArray = if (decoder is ByteBufDecoder) {
            decoder.decodeBoundedByteArray(decoder.packetLimits.maxNbtBytes)
        } else {
            decoder.decodeSerializableValue(byteArraySerializer)
        }

        val accounter = if (decoder is ByteBufDecoder) {
            NbtAccounter(decoder.packetLimits.maxNbtBytes.toLong())
        } else {
            NbtAccounter.UNLIMITED
        }
        return TagTypes.getType(type).load(ByteStreams.newDataInput(byteArray), 0, accounter)
    }

    private const val MIN_TAG_TYPE = 1
    private const val MAX_TAG_TYPE = 12
}
