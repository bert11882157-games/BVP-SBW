package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.effect.ShockwavePresentations
import com.atsuishio.superbwarfare.config.server.BlastConfig
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import com.atsuishio.superbwarfare.tools.sendPacketTo
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * One visible blast shockwave (TNT equivalent >= the shockwave threshold). Clients expand a translucent
 * hemispherical shell locally from [fromRadius] (the fireball) to [toRadius] (moderate damage radius).
 */
@Serializable
data class ShockwaveMessage(
    val position: SerializedVec3,
    val fromRadius: Float,
    val toRadius: Float,
    val durationTicks: Int,
    val maxParticles: Int,
    val seed: Long,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!valid()) return
        ShockwavePresentations.emit(this@ShockwaveMessage)
    }

    fun valid(): Boolean = position.x.isFinite() && position.y.isFinite() && position.z.isFinite() &&
        fromRadius.isFinite() && toRadius.isFinite() && fromRadius >= 0f && toRadius in 0.1f..MAX_RADIUS &&
        fromRadius <= toRadius && durationTicks in 1..200 && maxParticles in 1..4000

    companion object {
        const val MAX_RADIUS = 512f
        private const val AUDIENCE_RANGE = 512.0

        @JvmStatic
        fun send(level: ServerLevel, position: Vec3, plan: TntBlast.Plan) {
            val budget = BlastConfig.shockwaveMaxParticles()
            if (budget <= 0) return
            val message = ShockwaveMessage(
                position,
                plan.radii.fireball.toFloat(),
                plan.radii.moderate.toFloat().coerceAtMost(MAX_RADIUS),
                BlastConfig.shockwaveDurationTicks(),
                budget.coerceAtMost(4000),
                level.random.nextLong(),
            )
            if (!message.valid()) return
            val range = AUDIENCE_RANGE + message.toRadius
            for (player in level.players()) {
                if (!com.atsuishio.superbwarfare.api.vehicle.render.VehicleDeathEffects.allowsFullFx(player.uuid)) continue
                if (player.distanceToSqr(position) <= range * range) sendPacketTo(player, message)
            }
        }
    }
}
