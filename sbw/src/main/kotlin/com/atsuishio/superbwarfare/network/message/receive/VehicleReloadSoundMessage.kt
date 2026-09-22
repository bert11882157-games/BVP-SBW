package com.atsuishio.superbwarfare.network.message.receive

import com.atsuishio.superbwarfare.client.sound.VehicleReloadSounds
import com.atsuishio.superbwarfare.network.ClientPacketPayload
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import kotlinx.serialization.Serializable

/** One authoritative reload cycle, not a global play/stop command for a sound-event name. */
@Serializable
data class VehicleReloadSoundMessage(
    val cycleId: SerializedUUID,
    val dimension: SerializedResourceLocation,
    val vehicleId: Int,
    val vehicleUuid: SerializedUUID,
    val weaponIdentity: String,
    val reloadRevision: Int,
    val soundEvent: SerializedResourceLocation,
    val remainingTicks: Int,
    val localOnly: Boolean,
    val stop: Boolean,
) : ClientPacketPayload() {
    override fun PayloadContext.handler() {
        if (weaponIdentity.isBlank() || weaponIdentity.length > 128 ||
            reloadRevision <= 0 || remainingTicks !in 0..1200) return
        VehicleReloadSounds.handle(this@VehicleReloadSoundMessage)
    }
}
