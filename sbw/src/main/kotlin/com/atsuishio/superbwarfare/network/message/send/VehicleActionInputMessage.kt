package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import kotlinx.serialization.Serializable
import net.minecraft.resources.ResourceLocation

/** Context-bound action edge; the server re-derives the ridden vehicle and operator seat. */
@Serializable
data class VehicleActionInputMessage(
    val vehicleId: Int,
    val actionId: String,
    val sequence: Int,
    val held: Boolean,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        if (actionId.length > MAX_ACTION_ID_LENGTH) return
        val parsedActionId = ResourceLocation.tryParse(actionId) ?: return
        val player = sender()
        val vehicle = player.vehicle as? VehicleEntity ?: return
        vehicle.requestVehicleActionInput(player, vehicleId, parsedActionId, sequence, held)
    }

    companion object {
        private const val MAX_ACTION_ID_LENGTH = 128
    }
}
