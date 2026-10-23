package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.data.gun.GunData.Companion.from
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVector3f
import com.atsuishio.superbwarfare.tools.toVec3
import kotlinx.serialization.Serializable

@Serializable
data class ShootMessage(
    val spread: Double,
    val zoom: Boolean,
    val uuid: SerializedUUID?,
    val targetPos: SerializedVector3f?
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        val player = sender()
        val stack = player.mainHandItem
        if (stack.item !is GunItem) return

        // The client chooses the spread; bound it so absurd values cannot hang the server
        if (spread.isNaN()) return
        val clampedSpread = spread.coerceIn(0.0, 64.0)
        if (targetPos == null) {
            from(stack).shoot(player, clampedSpread, zoom, uuid)
        } else {
            from(stack).shoot(player, clampedSpread, zoom, uuid, targetPos.toVec3())
        }
    }
}
