package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.effect.FireballPresentations
import com.atsuishio.superbwarfare.config.server.BlastConfig
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import com.atsuishio.superbwarfare.tools.sendPacketTo
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * The visible fireball of one TNT-equivalent blast. Clients build a ball of glowing puffs whose outer edge is
 * exactly [radius] (the Hopkinson-Cranz fireball, R = k * W^(1/3)), then let it cool into soot.
 */
@Serializable
data class FireballMessage(
    val position: SerializedVec3,
    val radius: Float,
    val seed: Long,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!valid()) return
        FireballPresentations.emit(this@FireballMessage)
    }

    fun valid(): Boolean = position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
        radius.isFinite() && radius in MIN_RADIUS..MAX_RADIUS

    companion object {
        const val MIN_RADIUS = 0.01f
        const val MAX_RADIUS = 64f

        @JvmStatic
        fun send(level: ServerLevel, position: Vec3, radius: Double) {
            if (!BlastConfig.fireballVisible()) return
            val message = FireballMessage(position, radius.toFloat().coerceAtMost(MAX_RADIUS), level.random.nextLong())
            if (!message.valid()) return
            val range = BlastModel.fireballAudienceRange(radius)
            for (player in level.players()) {
                if (!com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects.allowsFullFx(player.uuid)) continue
                if (player.distanceToSqr(position) <= range * range) sendPacketTo(player, message)
            }
        }
    }
}
