package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.effect.RicochetTracerPresentations
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.atsuishio.superbwarfare.tools.sendPacketTo
import kotlinx.serialization.Serializable
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/**
 * A ricocheting round's tracer (owner 2026-09-30): the server eats the shot; clients draw a purely cosmetic tracer
 * leaving the plate at [velocity] (blocks per tick, already halved) that hits nothing and vanishes on terrain.
 */
@Serializable
data class RicochetTracerMessage(
    val position: SerializedVec3,
    val velocity: SerializedVec3,
    val red: Float,
    val green: Float,
    val blue: Float,
    val width: Float,
    val gravity: Float,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!valid()) return
        RicochetTracerPresentations.emit(this@RicochetTracerMessage)
    }

    fun valid(): Boolean = finite(position) && finite(velocity) && velocity.lengthSqr() <= MAX_SPEED * MAX_SPEED &&
        listOf(red, green, blue).all { it.isFinite() && it in 0f..1f } &&
        width.isFinite() && width in 0.01f..4f && gravity.isFinite() && gravity in 0f..1f

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

    companion object {
        const val MAX_SPEED = 64.0
        /** Players farther than this from the ricochet do not get its tracer (blocks). */
        const val AUDIENCE_RANGE = 256.0

        @JvmStatic
        fun send(level: ServerLevel, position: Vec3, velocity: Vec3, rgb: FloatArray, width: Float, gravity: Float) {
            val message = RicochetTracerMessage(position, velocity, rgb[0].coerceIn(0f, 1f), rgb[1].coerceIn(0f, 1f),
                rgb[2].coerceIn(0f, 1f), width.coerceIn(0.01f, 4f), gravity.coerceIn(0f, 1f))
            if (!message.valid()) return
            for (player in level.players()) {
                if (player.distanceToSqr(position) <= AUDIENCE_RANGE * AUDIENCE_RANGE) sendPacketTo(player, message)
            }
        }
    }
}
