package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import java.util.UUID

/** Stable selection and delayed breakup, replicated only when it changes; no debris entities. */
object AircraftWreckBreakup {
    const val LEFT = 1
    const val RIGHT = 2
    @JvmStatic fun detachedAt(vehicle: VehicleEntity, point: net.minecraft.world.phys.Vec3): Boolean {
        val missing = mask(vehicle)
        if (missing == 0) return false
        val side = if (point.x < 0) LEFT else RIGHT
        if (missing and side == 0) return false
        val id = if (side == LEFT) "superbwarfare:wing_left" else "superbwarfare:wing_right"
        val boxes = vehicle.computed().aircraftSurfaceModules.firstOrNull { it.id == id }?.hitboxes ?: return false
        if (boxes.isEmpty()) return false
        return point.x >= boxes.minOf { it.min.x } - .15 && point.x <= boxes.maxOf { it.max.x } + .15
    }
    @JvmStatic fun mask(id: UUID): Int = outcome(Math.floorMod(id.hashCode(), 100))
    @JvmStatic fun delayTicks(id: UUID): Int = 40 + Math.floorMod(id.mostSignificantBits xor
        (id.leastSignificantBits ushr 17), 161L).toInt()
    internal fun timedMask(id: UUID, age: Long, impacted: Boolean): Int {
        val initial = mask(id)
        if (initial != 0) return initial
        if (age < delayTicks(id) || impacted) return 0
        return if (id.mostSignificantBits and 1L == 0L) LEFT else RIGHT
    }
    fun update(vehicle: VehicleEntity) {
        if (vehicle.level().isClientSide || !vehicle.isWreck) return
        if (vehicle.aircraftWreckWings < 0) detach(vehicle, mask(vehicle.uuid))
        if (vehicle.crash && vehicle.hasRecentFixedWingWorldContact())
            detach(vehicle, LEFT or RIGHT)
        if (vehicle.aircraftWreckWings == 0 && !vehicle.sympatheticDetonated && !vehicle.onGround() && !vehicle.isInFluidType)
            detach(vehicle, timedMask(vehicle.uuid,
                vehicle.level().gameTime - vehicle.aircraftWreckStart, false))
    }
    fun detach(vehicle: VehicleEntity, sides: Int, momentum: net.minecraft.world.phys.Vec3 = vehicle.deltaMovement) {
        if (vehicle.level().isClientSide || !supported(vehicle)) return
        val previous = vehicle.aircraftWreckWings.coerceAtLeast(0)
        val next = previous or (sides and 3)
        if (vehicle.aircraftWreckStart < 0) vehicle.aircraftWreckStart = vehicle.level().gameTime
        if (next != previous || vehicle.aircraftWreckWings < 0) {
            vehicle.aircraftWreckMotionX = momentum.x.toFloat()
            vehicle.aircraftWreckMotionY = momentum.y.toFloat()
            vehicle.aircraftWreckMotionZ = momentum.z.toFloat()
            vehicle.aircraftWreckWings = next
            if (next != previous) {
                val level = vehicle.level() as? net.minecraft.server.level.ServerLevel
                if (level != null) {
                    val sound = net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(
                        net.minecraft.resources.ResourceLocation("berts_vehicle_pack", "impact_metal"))
                        ?: net.minecraft.sounds.SoundEvents.ANVIL_LAND
                    com.atsuishio.superbwarfare.tools.SoundTool.playDistantSound(
                        level, sound, vehicle.boundingBox.center, 10f, .8f, vehicle, vehicle,
                        channel = "aircraft_wing_shear")
                    val side = if ((next xor previous) and LEFT != 0) "superbwarfare:wing_left" else "superbwarfare:wing_right"
                    val position = com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.wreckFirePosition(vehicle,
                        net.minecraft.resources.ResourceLocation(side), 1f)
                        ?: vehicle.boundingBox.center
                    com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage.send(level,
                        com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage.Recipe.AIR_MISSILE,
                        position, vehicle.isInFluidType)
                }
            }
        }
    }
    internal fun outcome(percentile: Int): Int = when (percentile) {
        in 0..24 -> LEFT
        in 25..49 -> RIGHT
        in 50..69 -> LEFT or RIGHT
        else -> 0
    }
    private fun supported(vehicle: VehicleEntity): Boolean =
        vehicle.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE &&
        vehicle.computed().aircraftSurfaceModules.any { it.id == "superbwarfare:wing_left" } &&
        vehicle.computed().aircraftSurfaceModules.any { it.id == "superbwarfare:wing_right" }
    @JvmStatic fun mask(vehicle: VehicleEntity): Int = if (supported(vehicle))
        vehicle.aircraftWreckWings.takeIf { it >= 0 } ?: if (vehicle.isWreck) mask(vehicle.uuid) else 0 else 0
}
