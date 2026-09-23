package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/** Entry point for the control terminal. The server owns coordinates and revalidates the pilot. */
object AircraftBombTargeting {
    private const val KEY = "BvpBombGpsTarget"

    @JvmStatic fun setGpsTarget(player: ServerPlayer, aircraft: Entity, target: BlockPos): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        if (player.vehicle !== vehicle || vehicle.getSeatIndex(player) != 0 ||
            player.level() !== vehicle.level() || !player.isAlive || player.isSpectator ||
            !vehicle.isAlive || vehicle.isWreck || AircraftArmamentManager.definition(vehicle) == null ||
            target.y !in player.serverLevel().minBuildHeight until player.serverLevel().maxBuildHeight ||
            kotlin.math.abs(target.x) > 30_000_000 || kotlin.math.abs(target.z) > 30_000_000) return false
        vehicle.persistentData.putLong(KEY, target.asLong())
        return true
    }

    @JvmStatic fun clearGpsTarget(player: ServerPlayer, aircraft: Entity): Boolean {
        val vehicle = aircraft as? VehicleEntity ?: return false
        if (player.vehicle !== vehicle || vehicle.getSeatIndex(player) != 0 || player.level() !== vehicle.level()) return false
        vehicle.persistentData.remove(KEY)
        return true
    }

    @JvmStatic fun gpsTarget(vehicle: VehicleEntity): Vec3? =
        if (vehicle.persistentData.contains(KEY)) BlockPos.of(vehicle.persistentData.getLong(KEY)).center else null
}
