package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
import com.atsuishio.superbwarfare.client.particle.AircraftCombatParticles
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.UUID

/** Both native and far copies pass through the same renderer; UUIDs avoid handoff duplicates. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftWreckFire {
    private data class Seen(var vehicle: VehicleEntity, var observed: Long, var previous: List<Vec3> = emptyList())
    private val seen = LinkedHashMap<UUID, Seen>()
    private var level: Any? = null
    private var tick = 0L
    private fun broken(vehicle: VehicleEntity) = vehicle.isWreck ||
        com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle) != 0

    fun observe(vehicle: VehicleEntity) {
        val mc = Minecraft.getInstance()
        if (level !== mc.level) { seen.clear(); level = mc.level; tick = 0 }
        if (vehicle.level() !== mc.level || !broken(vehicle) || (vehicle.sympatheticDetonated && vehicle.aircraftWreckImpactTime < 0) ||
            (vehicle.vehicleType != VehicleType.AIRPLANE && vehicle.vehicleType != VehicleType.HELICOPTER)) return
        val old = seen[vehicle.uuid]
        if (old != null) { old.vehicle = vehicle; old.observed = tick; return }
        if (seen.size >= 32) seen.remove(seen.keys.first())
        seen[vehicle.uuid] = Seen(vehicle, tick)
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getInstance()
        if (level !== mc.level) { seen.clear(); level = mc.level; tick = 0 }
        if (mc.level == null || mc.isPaused) return
        tick++
        seen.entries.removeIf { (_, value) -> value.vehicle.isRemoved || !broken(value.vehicle) ||
            (value.vehicle.sympatheticDetonated && value.vehicle.aircraftWreckImpactTime < 0) || tick - value.observed > 4 }
        var budget = 96
        for ((uuid, value) in seen) {
            val vehicle = value.vehicle
            if (vehicle.aircraftWreckImpactTime >= 0) {
                val speed = vehicle.deltaMovement.horizontalDistance()
                if (vehicle.onGround() && speed > .02 && budget > 0) {
                    val section = vehicle.computed().aircraftTerrainContact?.wreckSections?.getOrNull(1)
                    if (section != null) {
                        val local = section.minimum.add(section.maximum).scale(.5)
                        val point = vehicle.getVehicleTransform(1f).transformPosition(org.joml.Vector3d(local.x,section.minimum.y,local.z))
                        AircraftCombatParticles.grindingSmoke(Vec3(point.x,point.y+.08,point.z),speed); budget--
                    }
                }
                continue
            }
            val intensity = com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckFlightStrategy
                .noseDownIntensity(vehicle.xRot.toDouble())
            val flameScale = (1 + intensity * 0.8).toFloat()
            val segments = 4 + (intensity * 2).toInt()
            val points = if (vehicle.isWreck) engines(vehicle) else emptyList()
            for ((index, point) in points.withIndex()) {
                val previous = value.previous.getOrNull(index)?.takeIf { it.distanceToSqr(point) < 256.0 } ?: point
                repeat(segments) { segment ->
                    if (budget > 0) {
                        AircraftCombatParticles.fire(previous.lerp(point, (segment + 1).toDouble() / segments), 2.8f * flameScale, false)
                        budget--
                    }
                }
                if (budget > 0) {
                    AircraftCombatParticles.fire(point, 4.6f * (1 + intensity * 0.25).toFloat(), true); budget--
                }
            }
            value.previous = points
            val phase = (tick + (uuid.leastSignificantBits and 15L)) % 16
            val detached = com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle)
            for ((index, id) in listOf(AircraftSurfaceModules.WING_LEFT, AircraftSurfaceModules.WING_RIGHT).withIndex()) {
                if (!vehicle.isWreck && detached and (1 shl index) == 0) continue
                if (detached and (1 shl index) == 0 && phase >= 7 + (intensity * 7).toInt()) continue
                val point = AircraftSurfaceModules.wreckFirePosition(vehicle, id, 1f) ?: continue
                if (budget > 1) {
                    AircraftCombatParticles.fire(point, 2.1f * flameScale, false)
                    if (tick % 2L == 0L) AircraftCombatParticles.fire(point, 2.8f * (1 + intensity * 0.25).toFloat(), true)
                    budget -= 2
                }
            }
        }
    }

    private fun engines(vehicle: VehicleEntity): List<Vec3> {
        val resource = VehicleResource.getDefault(vehicle)
        val afterburner = resource.afterburnerPresentation
        var locals = if (afterburner?.frame == "VEHICLE_LOCAL_BLOCKS")
            afterburner.outlets.orEmpty().take(4).mapNotNull { vector(it.position, false) } else emptyList()
        if (locals.isEmpty()) locals = resource.engineExhaust?.takeIf { it.parent == "HULL" }?.origins.orEmpty()
            .take(4).mapNotNull { vector(it.position, true) }
        if (locals.isEmpty()) locals = listOf(Vec3(0.0, vehicle.bbHeight * 0.45, -vehicle.bbWidth * 0.35))
        val transform = vehicle.getVehicleTransform(1f)
        return locals.map {
            val point = transform.transformPosition(org.joml.Vector3d(it.x, it.y, it.z))
            Vec3(point.x, point.y, point.z)
        }
    }

    private fun vector(values: DoubleArray?, flipZ: Boolean): Vec3? = values?.takeIf {
        it.size == 3 && it.all { value -> value.isFinite() && kotlin.math.abs(value) <= 64 }
    }?.let { Vec3(it[0], it[1], it[2] * if (flipZ) -1 else 1) }
}
