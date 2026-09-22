package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInputContext
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightTickResult
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3

/** A repeatable straight flight path; the real vehicle still owns movement, collision and damage. */
internal class AamTargetFlight(private val heading: Float) : VehicleFlightStrategy() {
    companion object {
        const val DEFAULT_DISTANCE = 160
        const val MIN_DISTANCE = 64
        const val MAX_DISTANCE = 512
        const val LIFETIME_TICKS = 1200L
        const val MAX_TARGETS = 4
        const val SPEED_BLOCKS_PER_TICK = 1.5

        fun spawnPosition(origin: Vec3, yaw: Float, distance: Int, terrainHeight: Int,
                          maxBuildHeight: Int): Vec3 {
            require(distance in MIN_DISTANCE..MAX_DISTANCE && yaw.isFinite())
            require(origin.x.isFinite() && origin.y.isFinite() && origin.z.isFinite())
            val ahead = origin.add(Vec3.directionFromRotation(0F, yaw).scale(distance.toDouble()))
            val altitude = maxOf(origin.y + 24.0, terrainHeight + 32.0)
            require(altitude <= maxBuildHeight - 16.0) { "Not enough airspace here; use a lower, open area." }
            return Vec3(ahead.x, altitude, ahead.z)
        }

        fun result(yaw: Float) = VehicleFlightTickResult(
            Vec3.directionFromRotation(0F, yaw).scale(SPEED_BLOCKS_PER_TICK),
            0.0, 0.0, 0.55, 0.55, yaw, 0F, 0F, true, 1.0)
    }

    override fun tickServer(vehicle: VehicleEntity, input: VehicleFlightInputContext) = result(heading)
}
