package com.atsuishio.superbwarfare.compat

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.receive.SonicParticleBurst
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraftforge.common.util.FakePlayerFactory
import net.minecraftforge.event.TickEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.registries.ForgeRegistries

/** Calls the separately installed SoundBarrier effect; no addon implementation or assets are bundled. */
object SoundBarrierCompat {
    private var unavailable = false
    fun commonSetup() {
        if (!ModList.get().isLoaded("supersonic") || net.minecraftforge.fml.loading.FMLEnvironment.dist !=
            net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) return
        // Forge SoundBarrier 1.4.0 registers its channel only in its client constructor branch.
        // Register the same public codec on a dedicated server; integrated servers already have it.
        Class.forName("com.example.examplemod.network.PacketHandler").getMethod("register").invoke(null)
    }
    private data class Capture(val vehicle: VehicleEntity, val bursts: MutableList<SonicParticleBurst> = ArrayList())
    private val capture = ThreadLocal<Capture>()
    private val nativeTick by lazy {
        Class.forName("com.example.examplemod.SonicEvents")
            .getMethod("onPlayerTick", TickEvent.PlayerTickEvent::class.java)
    }
    @JvmStatic fun dispatching() = capture.get() != null
    @JvmStatic fun effectVehicle(original: Entity): Entity = capture.get()?.vehicle ?: original
    @JvmStatic fun effectSpeed(original: Double): Double = if (dispatching()) 400.0 / 3.6 + .01 else original
    @JvmStatic fun suppressAutomatic(event: TickEvent.PlayerTickEvent): Boolean =
        !dispatching() && (event.player.vehicle as? VehicleEntity)?.isFixedWingFlightVehicle() == true

    @JvmStatic fun collect(type: ParticleOptions, x: Double, y: Double, z: Double, count: Int,
                           dx: Double, dy: Double, dz: Double, speed: Double): Boolean {
        val active = capture.get() ?: return false
        if (type !is SimpleParticleType) return true
        val id = ForgeRegistries.PARTICLE_TYPES.getKey(type.type)?.toString() ?: return true
        val burst = SonicParticleBurst(id, x, y, z, count, dx, dy, dz, speed)
        // The addon emits repeated identical random clouds; combine them without changing their distribution.
        val same = if (count > 0) active.bursts.indexOfFirst { it.copy(count = count) == burst && it.count > 0 } else -1
        if (same >= 0) active.bursts[same] = burst.copy(count = active.bursts[same].count + count)
        else active.bursts.add(burst)
        check(active.bursts.size <= 64 && active.bursts.sumOf { maxOf(1, it.count) } <= 512) {
            "Unsupported SoundBarrier particle layout"
        }
        return true
    }

    fun particles(vehicle: VehicleEntity): List<SonicParticleBurst> {
        if (unavailable || !ModList.get().isLoaded("supersonic")) return emptyList()
        val level = vehicle.level() as? ServerLevel ?: return emptyList()
        check(!dispatching())
        val active = Capture(vehicle)
        val data = vehicle.persistentData
        val oldState = data.get("is_supersonic")?.copy()
        val oldTime = data.get("last_sonic_boom")?.copy()
        try {
            capture.set(active)
            data.putBoolean("is_supersonic", false)
            data.putLong("last_sonic_boom", level.gameTime - 100)
            // An unspawned Forge fake player supplies only the addon's event context. No seat or entity is changed.
            nativeTick.invoke(null, TickEvent.PlayerTickEvent(TickEvent.Phase.END, FakePlayerFactory.getMinecraft(level)))
            check(active.bursts.isNotEmpty()) { "SoundBarrier did not supply its sonic effect" }
            return active.bursts.toList()
        } catch (failure: ReflectiveOperationException) {
            unavailable = true
            org.apache.logging.log4j.LogManager.getLogger("SBW SoundBarrier compatibility")
                .error("SoundBarrier effect dispatch failed; retaining the SBW fallback", failure)
            return emptyList()
        } catch (failure: IllegalStateException) {
            unavailable = true
            org.apache.logging.log4j.LogManager.getLogger("SBW SoundBarrier compatibility")
                .error("Unsupported SoundBarrier effect; retaining the SBW fallback", failure)
            return emptyList()
        } finally {
            if (oldState == null) data.remove("is_supersonic") else data.put("is_supersonic", oldState)
            if (oldTime == null) data.remove("last_sonic_boom") else data.put("last_sonic_boom", oldTime)
            capture.remove()
        }
    }
}
