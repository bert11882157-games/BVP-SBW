package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.projectile.MunitionFlightPlayback
import com.atsuishio.superbwarfare.client.MunitionFlightClient
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.tools.clientLevel
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

/**
 * Authoritative end-of-tick state of a server-steered munition (see [MunitionFlightPlayback]). [stamp] is the
 * server tick counter at publication; position in blocks, velocity in blocks per tick, angles in degrees.
 */
@Serializable
data class MunitionFlightMessage(
    val id: Int,
    val stamp: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val yRot: Float,
    val xRot: Float,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        val entity = clientLevel?.getEntity(id) as? FastThrowableProjectile ?: return
        if (entity.isRemoved || !entity.interpolatesFlight()) return
        MunitionFlightClient.accept(entity, MunitionFlightPlayback.Sample(stamp, Vec3(x, y, z),
            Vec3(vx.toDouble(), vy.toDouble(), vz.toDouble()), yRot, xRot))
    }

    companion object {
        @JvmStatic
        fun of(entity: FastThrowableProjectile, stamp: Int): MunitionFlightMessage {
            val motion = entity.deltaMovement
            return MunitionFlightMessage(entity.id, stamp, entity.x, entity.y, entity.z,
                motion.x.toFloat(), motion.y.toFloat(), motion.z.toFloat(), entity.yRot, entity.xRot)
        }
    }
}
