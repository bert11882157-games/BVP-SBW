package com.atsuishio.superbwarfare.init

import com.atsuishio.superbwarfare.client.sound.FastProjectileSoundInstance
import com.atsuishio.superbwarfare.client.sound.HornSoundInstance
import com.atsuishio.superbwarfare.client.sound.SteelCoilMoveSoundInstance
import com.atsuishio.superbwarfare.client.sound.VehicleFireSoundInstance
import com.atsuishio.superbwarfare.client.sound.VehicleLoopSoundProviderRegistry
import com.atsuishio.superbwarfare.client.sound.VehicleNativeLoopSoundLifecycle
import com.atsuishio.superbwarfare.client.sound.VehicleSoundInstance
import com.atsuishio.superbwarfare.entity.living.SteelCoilEntity
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.mc
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.function.BiConsumer
import java.util.function.Consumer

@OnlyIn(Dist.CLIENT)
object ModSoundInstances {
    fun init() {
        VehicleEntity.playTrackSound =
            Consumer { vehicle ->
                if (vehicle == null) return@Consumer
                VehicleNativeLoopSoundLifecycle.ensure(vehicle, VehicleNativeLoopSoundLifecycle.Channel.TRACK) {
                    VehicleSoundInstance.TrackSound(vehicle)
                }
            }
        VehicleEntity.playEngineSound =
            Consumer { vehicle ->
                if (vehicle == null) return@Consumer
                VehicleNativeLoopSoundLifecycle.ensure(vehicle, VehicleNativeLoopSoundLifecycle.Channel.ENGINE) {
                    VehicleSoundInstance.EngineSound(vehicle)
                }
            }
        VehicleEntity.authoredEngineAudio = java.util.function.Predicate {
            com.atsuishio.superbwarfare.client.sound.vehicle.VehicleAudioController.ownsEngine(it)
        }
        VehicleEntity.authoredTurretAudio = java.util.function.Predicate {
            com.atsuishio.superbwarfare.client.sound.vehicle.VehicleAudioController.ownsTurret(it)
        }
        VehicleEntity.tickCustomLoopSound =
            BiConsumer { vehicle, channel ->
                if (vehicle != null) VehicleLoopSoundProviderRegistry.tickLoop(vehicle, channel)
            }
        VehicleEntity.playSwimSound =
            Consumer { vehicle ->
                if (vehicle == null) return@Consumer
                VehicleNativeLoopSoundLifecycle.ensure(vehicle, VehicleNativeLoopSoundLifecycle.Channel.SWIM) {
                    VehicleSoundInstance.SwimSound(vehicle)
                }
            }
        VehicleEntity.playHornSound =
            Consumer { mc.soundManager.play(HornSoundInstance.VehicleHornSound(it)) }
        VehicleEntity.playStukaSound =
            Consumer { mc.soundManager.play(VehicleSoundInstance.StukaSound(it)) }
        VehicleEntity.playHeliCrashSound =
            Consumer { mc.soundManager.play(VehicleSoundInstance.HeliCrashSound(it)) }
        VehicleEntity.playVehicleSkipSound =
            Consumer { mc.soundManager.play(VehicleSoundInstance.SkipSound(it)) }
        VehicleEntity.playFireSound =
            Consumer { mc.soundManager.play(VehicleFireSoundInstance.VehicleFireSound(it)) }
        FastThrowableProjectile.playFlySound =
            Consumer { mc.soundManager.play(FastProjectileSoundInstance.FlySound(it)) }
        SteelCoilEntity.playMoveSound =
            Consumer { mc.soundManager.play(SteelCoilMoveSoundInstance.SteelCoilMoveSound(it)) }
    }
}
