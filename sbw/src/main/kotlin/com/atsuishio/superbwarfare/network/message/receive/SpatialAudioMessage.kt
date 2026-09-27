package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.sound.spatial.SpatialAudioPlayer
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import kotlinx.serialization.Serializable

/** One positional audio event and all of its distance variants; the client picks what it hears. */
@Serializable
data class SpatialAudioMessage(
    val firstPerson: SerializedResourceLocation?,
    val near: SerializedResourceLocation?,
    val far: SerializedResourceLocation?,
    val veryFar: SerializedResourceLocation?,
    val x: Double,
    val y: Double,
    val z: Double,
    val nearRange: Float,
    val farRange: Float,
    val maxRange: Float,
    val gain: Float,
    val pitch: Float,
    val sourceId: Int,
    val operatorId: Int,
    val category: Int,
    val weapon: String? = null,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        if (!(maxRange in 1f..4096f) || !(gain in 0f..8f) || !(pitch in 0.1f..8f)) return
        if (weapon != null && weapon.length > 96) return
        SpatialAudioPlayer.handle(this@SpatialAudioMessage)
    }
}
