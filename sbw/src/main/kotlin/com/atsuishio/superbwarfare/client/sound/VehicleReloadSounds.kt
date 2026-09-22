package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.api.weapon.AudioPlaybackRegistry
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.VehicleReloadSoundMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import java.util.UUID

/** Client playback lives exactly as long as its originating reload cycle and vehicle. */
object VehicleReloadSounds {
    private val instances = AudioPlaybackRegistry<ReloadSound> {
        Minecraft.getInstance().soundManager.stop(it)
    }

    fun handle(message: VehicleReloadSoundMessage) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        if (level.dimension().location() != message.dimension) return
        if (message.stop) {
            instances.stop(message.cycleId)
            return
        }
        val vehicle = level.getEntity(message.vehicleId) as? VehicleEntity ?: return
        if (vehicle.uuid != message.vehicleUuid || message.remainingTicks <= 0) return
        instances.start(message.cycleId) {
            ReloadSound(message, vehicle).also(minecraft.soundManager::play)
        }
    }

    fun detachListener(vehicleUuid: UUID) {
        instances.stopMatching { it.origin.vehicleUuid == vehicleUuid && it.origin.localOnly }
    }

    fun clear() = instances.clear()

    class ReloadSound(val origin: VehicleReloadSoundMessage, private val vehicle: VehicleEntity) :
        AbstractTickableSoundInstance(
            SoundEvent.createVariableRangeEvent(origin.soundEvent), SoundSource.PLAYERS,
            RandomSource.create(),
        ), AttributedVehicleSound {
        override fun eliteSourceEntity() = origin.vehicleUuid
        override fun eliteWeapon() = origin.weaponIdentity
        override fun eliteChannel() = "reload"
        override fun eliteCycle() = origin.cycleId
        override fun eliteReloadRevision() = origin.reloadRevision
        private var remaining = origin.remainingTicks

        init {
            looping = false
            delay = 0
            volume = if (origin.localOnly) 3f else 2f
            pitch = 1f
            relative = origin.localOnly
            attenuation = if (origin.localOnly) SoundInstance.Attenuation.NONE else SoundInstance.Attenuation.LINEAR
            updatePosition()
        }

        private fun updatePosition() {
            x = if (origin.localOnly) 0.0 else vehicle.x
            y = if (origin.localOnly) 0.0 else vehicle.y
            z = if (origin.localOnly) 0.0 else vehicle.z
        }

        override fun tick() {
            val client = Minecraft.getInstance()
            if (vehicle.isRemoved || client.level !== vehicle.level() ||
                (origin.localOnly && client.player?.vehicle !== vehicle) || --remaining <= 0) {
                stop()
                instances.retire(origin.cycleId)
                return
            }
            updatePosition()
        }
    }
}
