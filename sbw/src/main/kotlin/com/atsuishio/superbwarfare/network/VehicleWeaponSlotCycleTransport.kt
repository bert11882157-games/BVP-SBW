package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.send.CycleVehicleWeaponSlotMessage
import net.minecraft.network.Connection
import net.minecraft.server.level.ServerPlayer
import java.util.WeakHashMap

/**
 * Connection-scoped admission for the primary/secondary slot-cycle edge. The packet is a
 * request only: entity data remains the sole selected-slot state distributed to clients.
 */
object VehicleWeaponSlotCycleTransport {
    const val MAX_SEAT_INDEX = 2_047

    private val lastAcceptedSequence = WeakHashMap<Connection, Long>()

    /**
     * Validates every claimed context before invoking the slot mutator exactly once. Rejected
     * packets never mutate the vehicle or the connection watermark.
     */
    @Synchronized
    @JvmStatic
    fun admit(player: ServerPlayer, message: CycleVehicleWeaponSlotMessage): Boolean {
        if (!player.isAlive || player.isSpectator || player.isRemoved) return false
        if (message.vehicleId < 0 || message.seatIndex !in 0..MAX_SEAT_INDEX || message.sequence <= 0L ||
            message.direction !in setOf(-1, 1)) {
            return false
        }

        val vehicle = player.vehicle as? VehicleEntity ?: return false
        if (vehicle.id != message.vehicleId || vehicle.uuid != message.vehicleUuid ||
            vehicle.level() !== player.level() ||
            vehicle.level().dimension().location() != message.dimension ||
            vehicle.isRemoved || vehicle.isWreck
        ) return false
        if (vehicle.getSeatIndex(player) != message.seatIndex ||
            vehicle.getNthEntity(message.seatIndex) !== player
        ) return false

        val connection = player.connection.connection
        val previous = lastAcceptedSequence[connection]
        if (previous != null && message.sequence <= previous) return false

        // Consume the connection-local edge before the sole mutator call. A no-op cycle still
        // consumes its sequence so retries cannot replay an admitted request.
        lastAcceptedSequence[connection] = message.sequence
        return vehicle.cycleWeaponSlot(message.seatIndex, message.slot, message.direction)
    }
}
