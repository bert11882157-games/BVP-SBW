package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.config.server.FarRenderConfig
import com.atsuishio.superbwarfare.config.server.VehicleConfig
import com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.server.level.ServerLevel
import kotlin.math.abs

/** Far residency keeps parked aircraft available without promoting their chunks to full simulation. */
object FarVehicleSimulationPolicy {
    fun shouldRenewSimulationTicket(vehicle: VehicleEntity): Boolean {
        val level = vehicle.level() as? ServerLevel ?: return false
        if (!FarRenderConfig.ENABLED.get() || !vehicle.isFixedWingFlightVehicle()) return true
        if (!canRest(vehicle.tickCount, vehicle.onGround(), vehicle.passengers.isNotEmpty(),
                vehicle.engineRunning(), vehicle.isWreck, vehicle.isOnFire, vehicle.health.toDouble(),
                vehicle.getMaxHealth().toDouble(), vehicle.deltaMovement.lengthSqr())) return true
        for (id in AircraftSurfaceModules.ids) {
            val state = AircraftSurfaceModules.state(vehicle, id) ?: continue
            if (state.destroyed || !state.health.isFinite() || state.health < state.maxHealth) return true
        }

        val position = vehicle.chunkPosition()
        val distance = level.server.playerList.simulationDistance
        for (player in level.players()) {
            val playerChunk = player.chunkPosition()
            if (insideSimulation(position.x, position.z, playerChunk.x, playerChunk.z, distance)) return true
        }
        return false
    }

    /** Test the completed movement, not the flight solver's pre-contact gravity velocity. */
    internal fun canRest(ageTicks: Int, grounded: Boolean, occupied: Boolean, engineRunning: Boolean,
                         wreck: Boolean, burning: Boolean, health: Double, maxHealth: Double,
                         speedSquared: Double): Boolean =
        ageTicks >= 100 && grounded && !occupied && !engineRunning && !wreck && !burning &&
            health.isFinite() && maxHealth.isFinite() && maxHealth > 0.0 && health >= maxHealth &&
            speedSquared.isFinite() && speedSquared in 0.0..0.000001

    // One chunk of margin avoids ticket churn at the vanilla simulation boundary.
    internal fun insideSimulation(x: Int, z: Int, playerX: Int, playerZ: Int, distance: Int): Boolean =
        abs(x.toLong() - playerX) <= distance + 1L && abs(z.toLong() - playerZ) <= distance + 1L

    /** A real distant hit must resume damage/fire/destruction lifecycle processing. */
    fun wakeAfterDamage(vehicle: VehicleEntity, damage: Float) {
        if (damage <= 0f || vehicle.isRemoved || vehicle.level() !is ServerLevel ||
            !vehicle.isFixedWingFlightVehicle() || !FarRenderConfig.ENABLED.get() ||
            !VehicleConfig.VEHICLE_CHUNK_LOADING.get() || !vehicle.computed().keepChunkLoaded) return
        vehicle.keepChunkLoaded(vehicle.position())
    }
}
