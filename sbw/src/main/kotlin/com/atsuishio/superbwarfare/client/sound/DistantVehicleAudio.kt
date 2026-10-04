package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.FarVehicleClient
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.sounds.SoundEvent
import net.minecraft.util.RandomSource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID
import kotlin.math.*

/** Bounded far-engine prototype. Uses received snapshots, even when the vehicle is off screen. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object DistantVehicleAudio {
    private val loops = LinkedHashMap<UUID, EngineLoop>()
    private val providers = LinkedHashMap<String, java.util.function.Function<VehicleEntity, SoundEvent?>>()
    @JvmStatic fun registerEngineProvider(id: String, provider: java.util.function.Function<VehicleEntity, SoundEvent?>) {
        providers[id] = provider
    }
    private var level: Any? = null
    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        if (level !== mc.level) { loops.values.forEach(mc.soundManager::stop); loops.clear(); level = mc.level }
        val world = mc.level ?: return
        if (mc.isPaused) return
        val eye = mc.gameRenderer.mainCamera.position
        val wanted = HashSet<UUID>()
        // At most twelve candidate copies are resolved. No all-roster creation or world scanning.
        val candidates = FarVehicleClient.store.values().asSequence()
            .filter { !it.current.wreck && it.current.power > .01f && it.current.distanceSquared(eye.x,eye.y,eye.z) <= 1600.0*1600.0 }
            .sortedBy { it.current.distanceSquared(eye.x,eye.y,eye.z) }.take(12)
        for (entry in candidates) {
            val vehicle = FarVehicleClient.resolve(entry,1f) ?: continue
            if (mc.player?.let(vehicle::hasPassenger) == true) continue
            // a profile with a far layer is voiced by the authored-profile controller while the vehicle is loaded
            val authored = com.atsuishio.superbwarfare.client.sound.vehicle.VehicleAudioController.distantLayer(vehicle)
            if (authored != null &&
                com.atsuishio.superbwarfare.client.sound.vehicle.VehicleAudioController.voices(vehicle.uuid)) continue
            val jet = vehicle.vehicleType == VehicleType.AIRPLANE
            val sound = if (authored != null) SoundEvent.createVariableRangeEvent(authored.loop) else if (jet) {
                val strategy = vehicle.resolveVehicleFlightStrategy() as? com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy ?: continue
                if (strategy.handling.propellerPowerReferenceSpeedMps > 0) continue
                ModSounds.DISTANT_JET_ENGINE.get()
            } else {
                if (vehicle.vehicleType !in setOf(VehicleType.TANK, VehicleType.APC, VehicleType.AA, VehicleType.CAR, VehicleType.HELICOPTER)) continue
                if (vehicle.position().distanceToSqr(eye) > 512.0*512.0) continue
                providers.values.firstNotNullOfOrNull { it.apply(vehicle) } ?: vehicle.getEngineSound() ?: continue
            }
            wanted.add(vehicle.uuid)
            val old = loops[vehicle.uuid]
            if (old == null || old.isStopped) {
                EngineLoop(vehicle, sound, jet, authored?.range).also { loops[vehicle.uuid] = it; mc.soundManager.play(it) }
            } else old.vehicle = vehicle
            if (jet && world.gameTime % 4L == 0L && vehicle.deltaMovement.lengthSqr() > 2.25 &&
                vehicle.position().distanceToSqr(eye) < 128.0*128.0) {
                val block = vehicle.blockPosition()
                if (world.hasChunkAt(block)) {
                    val ground = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,block.x,block.z)
                    if (vehicle.y-ground in 1.0..18.0) {
                        val state=world.getBlockState(net.minecraft.core.BlockPos(block.x,ground-1,block.z))
                        if (!state.isAir) repeat(3) {
                            world.addParticle(net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,state),
                                vehicle.x+(it-1)*1.2,ground+.06,vehicle.z,(it-1)*.07,.06,0.0)
                        }
                    }
                }
            }
        }
        loops.entries.removeIf { (id,sound) ->
            if (id !in wanted) { mc.soundManager.stop(sound); true } else false
        }
    }
    /** [authoredRange]: the profile's far layer range; its level then follows the controller's far curve. */
    private class EngineLoop(var vehicle: VehicleEntity, sound: SoundEvent, val jet: Boolean,
                             val authoredRange: Float? = null) : AbstractTickableSoundInstance(
        sound, SoundSource.NEUTRAL, RandomSource.create()), com.atsuishio.superbwarfare.client.sound.spatial.DopplerSound,
        com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.Tagged {
        override fun vehicleMixCategory() = com.atsuishio.superbwarfare.client.sound.VehicleAudioMix.engineOf(vehicle)
        override fun dopplerVelocity(): net.minecraft.world.phys.Vec3? {
            val moved = com.atsuishio.superbwarfare.client.sound.spatial.SpatialDoppler.entityVelocity(vehicle)
            return if (moved.lengthSqr() > 1e-8) moved else vehicle.deltaMovement
        }
        init { looping = true; delay = 0; attenuation = SoundInstance.Attenuation.NONE; volume = 0f }
        override fun canStartSilent() = true
        override fun tick() {
            val mc = Minecraft.getInstance()
            if (vehicle.level() !== mc.level || vehicle.isRemoved || vehicle.isWreck) { stop(); return }
            x=vehicle.x; y=vehicle.y; z=vehicle.z
            val offset=vehicle.position().subtract(mc.gameRenderer.mainCamera.position)
            val distance=offset.length()
            val speed=vehicle.deltaMovement.length()
            val nearFade=((distance-64)/96).coerceIn(0.0,1.0)
            val rangeFade=(1-distance/(if(jet) 1600 else 512)).coerceIn(0.0,1.0)
            val flyby = if (jet && speed > 1.5) .35*(1-distance/128).coerceIn(0.0,1.0) else 0.0
            volume = if (authoredRange != null) {
                // same curve as the controller's far layer, so the hand-over at the loading edge is level
                (com.atsuishio.superbwarfare.client.sound.spatial.SpatialAudioPlayer.gainAt(distance, authoredRange.toDouble()) * 0.85).toFloat()
            } else ((if(jet) .24 else .16)*nearFade*rangeFade*rangeFade+flyby).toFloat()
            // Doppler comes from OpenAL (dopplerVelocity); the pitch only follows engine speed.
            pitch=(.75+min(.3,speed*.08)).toFloat()
        }
    }
}
