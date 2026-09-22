package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures
import com.atsuishio.superbwarfare.client.FarEffectsClient
import com.atsuishio.superbwarfare.client.particle.AircraftCombatParticles
import com.atsuishio.superbwarfare.client.particle.ChaffBurstParticle
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.entity.projectile.FlareDecoyEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModParticleTypes
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Vector3d
import java.util.UUID

/** Presentation only: accepted countermeasure levels, lifetimes and FFA diversion remain server-owned. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftCountermeasureEffects {
    private data class Seen(var vehicle: VehicleEntity, var observed: Long, var nextBurst: Long = 0)
    private val seen = LinkedHashMap<UUID, Seen>()
    private var level: Any? = null
    private var budgetTick = Long.MIN_VALUE
    private var budget = 0

    private fun admit(cost: Int): Boolean {
        val current = Minecraft.getInstance().level ?: return false
        if (level !== current) { seen.clear(); level = current; budgetTick = Long.MIN_VALUE }
        if (budgetTick != current.gameTime) { budgetTick = current.gameTime; budget = 80 }
        if (budget < cost) return false
        budget -= cost
        return true
    }

    fun flareTrail(flare: FlareDecoyEntity) {
        val previous = Vec3(flare.xo, flare.yo, flare.zo)
        val point = flare.position()
        if (previous.distanceToSqr(point) > 256 || !admit(6)) return
        // TaP EntityFlare emits five FMFlame samples along each movement segment.
        repeat(5) { AircraftCombatParticles.fire(previous.lerp(point, it / 5.0), 1.2f, false) }
        if (flare.tickCount % 2 == 0) AircraftCombatParticles.fire(point, 1.5f, true)
    }

    fun observe(vehicle: VehicleEntity) {
        val mc = Minecraft.getInstance()
        if (level !== mc.level) { seen.clear(); level = mc.level; budgetTick = Long.MIN_VALUE }
        if (vehicle.level() !== mc.level || vehicle.isWreck || !vehicle.isChaffEmitting()) return
        val now = mc.level?.gameTime ?: return
        val old = seen[vehicle.uuid]
        if (old != null) { old.vehicle = vehicle; old.observed = now; return }
        if (seen.size >= 32) seen.remove(seen.keys.first())
        seen[vehicle.uuid] = Seen(vehicle, now)
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        if (mc.level == null) { seen.clear(); level = null; return }
        if (mc.isPaused) return
        val now = mc.level!!.gameTime
        // Reset before iteration; admit() must never invalidate an active map iterator.
        if (level !== mc.level) { seen.clear(); level = mc.level; budgetTick = Long.MIN_VALUE }
        seen.entries.removeIf { (_, value) -> value.vehicle.isRemoved || value.vehicle.isWreck ||
            !value.vehicle.isChaffEmitting() || now - value.observed > 4 }
        for (value in seen.values) {
            if (now < value.nextBurst) continue
            value.nextBurst = now + 6
            val vehicle = value.vehicle
            val definition = AircraftCountermeasures.definition(vehicle) ?: continue
            val strength = vehicle.getChaffLevel().coerceIn(0, 12) / 12f
            val transform = vehicle.getVehicleTransform(1f)
            for ((index, local) in listOf(definition.flareLeftPos, definition.flareRightPos).withIndex()) {
                if (!admit(4)) break
                val point = transform.transformPosition(Vector3d(local.x, local.y, local.z))
                val side = transform.transformDirection(Vector3d(if (index == 0) -.18 else .18, -.035, .07))
                val drift = vehicle.deltaMovement.scale(.25).add(side.x, side.y, side.z)
                val foil = mc.particleEngine.createParticle(ModParticleTypes.CHAFF_BURST.get(),
                    point.x, point.y, point.z, drift.x, drift.y, drift.z) as? ChaffBurstParticle
                if (foil != null) { foil.strength = strength; FarEffectsClient.retainParticle(foil) }
                repeat(3) { ring ->
                    AircraftCombatParticles.emit(CustomCloudOption(.58f, .63f, .68f, 22,
                        5f + strength * 5f, 0f, false, true),
                        Vec3(point.x + side.x * ring, point.y + side.y * ring, point.z + side.z * ring))
                }
            }
        }
    }
}
