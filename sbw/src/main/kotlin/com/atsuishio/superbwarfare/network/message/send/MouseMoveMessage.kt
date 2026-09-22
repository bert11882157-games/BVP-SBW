package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import kotlinx.serialization.Serializable

@Serializable
data class MouseMoveMessage(val speedX: Double, val speedY: Double) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        if (!speedX.isFinite() || !speedY.isFinite()) return

        val player = sender()
        val boundedX = speedX.coerceIn(-MAX_MOUSE_SPEED, MAX_MOUSE_SPEED)
        val boundedY = speedY.coerceIn(-MAX_MOUSE_SPEED, MAX_MOUSE_SPEED)
        val stack = player.mainHandItem
        val tag = stack.getOrCreateTag()

        // ClientMouseHandler gives an active linked monitor exclusive mouse focus.
        if (stack.`is`(ModItems.MONITOR.get()) && tag.getBoolean("Using") && tag.getBoolean("Linked")) {
            val drone = EntityFindUtil.findDrone(player.level(), tag.getString("LinkedDrone"))
            if (drone != null) {
                drone.mouseInput(boundedX, boundedY)
            }
            return
        }

        val entity = player.vehicle
        if (entity is VehicleEntity && entity.firstPassenger === player) {
            entity.acceptVehicleMouseInput(player, boundedX, boundedY)
        }
    }

    companion object {
        // Raw deltas are clamped to +/-256 client-side; 512 also preserves the normalized-roll
        // axis blend while still bounding forged packet magnitudes conservatively.
        private const val MAX_MOUSE_SPEED = 512.0
    }
}
