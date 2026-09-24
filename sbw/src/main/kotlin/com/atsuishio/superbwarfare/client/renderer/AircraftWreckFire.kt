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
        if (vehicle.level() !== mc.level || !broken(vehicle)) return
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
        seen.entries.removeIf { (_, value) -> value.vehicle.isRemoved || !broken(value.vehicle) || tick - value.observed > 4 }
        var budget = 64
        for ((uuid, value) in seen) {
            val vehicle = value.vehicle
            val groundedWreck = vehicle.isWreck && (vehicle.onGround() || vehicle.isInFluidType ||
                vehicle.aircraftWreckImpactTime >= 0 || vehicle.vehicleType !in setOf(VehicleType.AIRPLANE, VehicleType.HELICOPTER))
            if (groundedWreck) {
                val age = (vehicle.level().gameTime - vehicle.aircraftWreckStart).coerceAtLeast(0)
                val flame = com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics.flameStrength(age, uuid.leastSignificantBits)
                // Small, localized emitters replace the old large cloud/flame field. Cycling points
                // spreads the burn over the hull without multiplying work by vehicle size.
                val section = vehicle.computed().aircraftTerrainContact?.wreckSections?.getOrNull(1)
                val index = Math.floorMod(tick + uuid.leastSignificantBits, 7L).toDouble() / 6.0
                val local = if (section != null) Vec3(
                    section.minimum.x + (section.maximum.x-section.minimum.x)*index,
                    (section.minimum.y+section.maximum.y)*.5,
                    section.minimum.z + (section.maximum.z-section.minimum.z)*(1-index))
                else Vec3((index-.5)*vehicle.bbWidth*.6,vehicle.bbHeight*.5,(.5-index)*vehicle.bbWidth*.6)
                val transformed = vehicle.getVehicleTransform(1f).transformPosition(org.joml.Vector3d(local.x,local.y,local.z))
                val surfaceY = if (section == null) vehicle.boundingBox.maxY else {
                    val transform = vehicle.getVehicleTransform(1f)
                    (0..7).maxOf { bits -> transform.transformPosition(org.joml.Vector3d(
                        if (bits and 1 == 0) section.minimum.x else section.maximum.x,
                        if (bits and 2 == 0) section.minimum.y else section.maximum.y,
                        if (bits and 4 == 0) section.minimum.z else section.maximum.z)).y }
                }
                val point = Vec3(transformed.x,maxOf(transformed.y,surfaceY+.08),transformed.z)
                val phase = tick + uuid.leastSignificantBits
                if (budget > 0 && Math.floorMod(phase,3L) == 0L && flame > .01f) {
                    AircraftCombatParticles.wreckFlame(point,flame); budget--
                }
                if (budget > 0 && Math.floorMod(phase,2L) == 0L) {
                    val groundVehicle = vehicle.vehicleType !in setOf(VehicleType.AIRPLANE,VehicleType.HELICOPTER)
                    val diameter = if (groundVehicle) 2.7f+(1-flame)*1.2f else 1.8f+(1-flame)*.8f
                    AircraftCombatParticles.wreckSmoke(point.add(0.0,.2,0.0),diameter); budget--
                }
                val speed = vehicle.deltaMovement.horizontalDistance()
                if (vehicle.onGround() && speed > .02 && budget > 0) {
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
            val segments = 2 + intensity.toInt()
            val points = if (vehicle.isWreck) engines(vehicle) else emptyList()
            for ((index, point) in points.withIndex()) {
                val previous = value.previous.getOrNull(index)?.takeIf { it.distanceToSqr(point) < 256.0 } ?: point
                repeat(segments) { segment ->
                    if (budget > 0) {
                        AircraftCombatParticles.fire(previous.lerp(point, (segment + 1).toDouble() / segments), 1.4f * flameScale, false)
                        budget--
                    }
                }
                if (budget > 0) {
                    AircraftCombatParticles.fire(point, 2.8f * (1 + intensity * 0.25).toFloat(), true); budget--
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
                    AircraftCombatParticles.fire(point, 1.1f * flameScale, false)
                    if (tick % 2L == 0L) AircraftCombatParticles.fire(point, 2.1f * (1 + intensity * 0.25).toFloat(), true)
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
