package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.api.projectile.SmoothedBallisticProjectile
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.tools.clientLevel
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Velocity correction. Deterministic ballistic rounds also carry the simulation step and position of
 * the same state, so the client aligns it to its own step instead of snapping to a late position.
 */
@Serializable
data class ClientMotionSyncMessage(
    val id: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val tick: Int = -1,
    val positioned: Boolean = false,
    val px: Double = 0.0,
    val py: Double = 0.0,
    val pz: Double = 0.0,
) : ClientPacketPayload() {

    constructor(id: Int, motion: Vec3) : this(id, motion.x.toFloat(), motion.y.toFloat(), motion.z.toFloat())
    constructor(entity: Entity) : this(entity.id, entity.deltaMovement)

    override fun PayloadContext.handler() {
        val entity = clientLevel?.getEntity(id) ?: return
        if (positioned && entity is SmoothedBallisticProjectile && entity.smoothsBallisticFlight() &&
            px.isFinite() && py.isFinite() && pz.isFinite() && x.isFinite() && y.isFinite() && z.isFinite()
        ) {
            entity.acceptBallisticState(tick, Vec3(px, py, pz), Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
            return
        }
        entity.lerpMotion(x.toDouble(), y.toDouble(), z.toDouble())
    }

    companion object {
        /** Step-aligned position and velocity of a deterministic ballistic round. */
        @JvmStatic
        fun ballistic(entity: Entity): ClientMotionSyncMessage {
            val motion = entity.deltaMovement
            return ClientMotionSyncMessage(
                entity.id, motion.x.toFloat(), motion.y.toFloat(), motion.z.toFloat(),
                entity.tickCount, true, entity.x, entity.y, entity.z,
            )
        }
    }
}
