package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.tools.clientLevel
import kotlinx.serialization.Serializable

/**
 * A blast push for a vehicle whose movement is simulated by its driver's client (the server's motion would be
 * overwritten by the driver's next move packet). Velocity in blocks/tick, spins in degrees/tick.
 */
@Serializable
data class VehicleBlastImpulseMessage(
    val entityId: Int,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val yawSpin: Float,
    val pitchSpin: Float,
    val rollSpin: Float,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (!valid()) return
        val vehicle = clientLevel?.getEntity(entityId) as? VehicleEntity ?: return
        if (!vehicle.isControlledByLocalInstance) return
        vehicle.receiveBlastImpulse(vx.toDouble(), vy.toDouble(), vz.toDouble(), yawSpin, pitchSpin, rollSpin)
    }

    fun valid(): Boolean = listOf(vx, vy, vz, yawSpin, pitchSpin, rollSpin).all { it.isFinite() && it in -64f..64f }
}
