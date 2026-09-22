package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.send.CycleVehicleWeaponSlotMessage
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn

/** Client edge API for FX/key consumers; the server remains the slot-selection authority. */
@OnlyIn(Dist.CLIENT)
object VehicleWeaponSlotCycleClient {
    private var observedConnection: ClientPacketListener? = null
    private var nextSequence = 0L

    /** Sends one authenticated cycle request for the mounted player's current vehicle seat. */
    @JvmStatic
    fun request(slot: VehicleWeaponSlot): Boolean {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        val connection = minecraft.connection ?: return false
        val seatIndex = vehicle.getSeatIndex(player)
        if (!player.isAlive || player.isSpectator || player.isRemoved || vehicle.isRemoved ||
            vehicle.isWreck || vehicle.level() !== player.level() || seatIndex < 0 ||
            vehicle.getNthEntity(seatIndex) !== player
        ) return false

        if (observedConnection !== connection) {
            observedConnection = connection
            nextSequence = 0L
        }
        if (nextSequence == Long.MAX_VALUE) return false
        nextSequence += 1L
        sendPacketToServer(
            CycleVehicleWeaponSlotMessage(
                vehicle.id,
                vehicle.uuid,
                vehicle.level().dimension().location(),
                seatIndex,
                slot,
                nextSequence,
            )
        )
        return true
    }

    /** Explicit lifecycle reset for logout/disconnect hooks; connection identity also resets it. */
    @JvmStatic
    fun clear() {
        observedConnection = null
        nextSequence = 0L
    }
}
