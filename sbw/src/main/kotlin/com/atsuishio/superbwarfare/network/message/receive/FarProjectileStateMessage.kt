package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.FarProjectilePlayback
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity

/** Reliable simulation edges, not a second movement stream. UUID prevents recycled-ID corruption. */
@Serializable
data class FarProjectileStateMessage(
    val id: Int, val uuid: String, val paused: Boolean,
    val x: Double, val y: Double, val z: Double,
    val vx: Double, val vy: Double, val vz: Double,
) : ClientPacketPayload() {
    constructor(entity: Entity, paused: Boolean) : this(entity.id, entity.stringUUID, paused,
        entity.x, entity.y, entity.z, entity.deltaMovement.x, entity.deltaMovement.y, entity.deltaMovement.z)

    fun valid(): Boolean = uuid.length == 36 && listOf(x, y, z, vx, vy, vz).all(Double::isFinite) &&
        kotlin.math.abs(x) <= 30_000_000 && kotlin.math.abs(z) <= 30_000_000 &&
        kotlin.math.abs(y) <= 20_000_000

    override fun PayloadContext.handler() { if (valid()) FarProjectilePlayback.accept(this@FarProjectileStateMessage) }
}
