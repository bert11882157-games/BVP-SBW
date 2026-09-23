package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.network.VehicleWeaponSlotCycleTransport
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/** Authenticated one-shot primary/secondary slot-cycle edge; no client state is authoritative. */
@Serializable
data class CycleVehicleWeaponSlotMessage(
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val seatIndex: Int,
    val slot: VehicleWeaponSlot,
    val sequence: Long,
    val direction: Int = 1,
) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        VehicleWeaponSlotCycleTransport.admit(sender(), this@CycleVehicleWeaponSlotMessage)
    }
}
