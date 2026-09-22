package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraftforge.client.settings.IKeyConflictContext
import net.minecraftforge.client.settings.KeyConflictContext

/** Input context for a mounted SBW vehicle or an actively linked remote vehicle monitor. */
object VehicleKeyConflictContext : IKeyConflictContext {
    override fun isActive(): Boolean {
        val minecraft = Minecraft.getInstance()
        if (minecraft.screen != null || !minecraft.isWindowActive) return false
        val player = minecraft.player ?: return false
        if (!player.isAlive || player.isSpectator) return false
        return player.vehicle is VehicleEntity || controlsLinkedMonitor(player)
    }

    override fun conflicts(other: IKeyConflictContext): Boolean = other === this

    private fun controlsLinkedMonitor(player: Player): Boolean = VehicleControlProfile.controlsLinkedDrone(player)
}

/** Conflict membership is independent of runtime activity, including the unmounted Controls UI. */
abstract class VehicleProfileKeyConflictContext(
    private val profile: VehicleControlProfile,
) : IKeyConflictContext {
    override fun isActive(): Boolean {
        if (!VehicleKeyConflictContext.isActive()) return false
        val player = Minecraft.getInstance().player ?: return false
        return VehicleControlProfile.resolve(player) == profile
    }

    override fun conflicts(other: IKeyConflictContext): Boolean =
        other is VehicleProfileKeyConflictContext && profile == other.profile
}

object LandVehicleKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.LAND)
object PlanePilotKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.PLANE)
object HelicopterPilotKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.HELICOPTER)
object DroneKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.DRONE)

/** Claims aircraft controls only while the local player occupies a fixed-wing pilot seat. */
object FixedWingPilotKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.PLANE) {
    override fun isActive(): Boolean {
        if (!super.isActive()) return false
        val player = Minecraft.getInstance().player ?: return false
        val vehicle = player.vehicle as? VehicleEntity ?: return false
        return player.isAlive && !player.isSpectator &&
            vehicle.isFixedWingFlightVehicle() && vehicle.getNthEntity(0) === player
    }
}

/** Retractable gear is available only on the authored fixed-wing pilot context. */
object FixedWingLandingGearKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.PLANE) {
    override fun isActive(): Boolean {
        if (!FixedWingPilotKeyConflictContext.isActive()) return false
        val vehicle = Minecraft.getInstance().player?.vehicle as? VehicleEntity ?: return false
        return vehicle.hasFixedWingLandingGear()
    }
}

/** Keeps the native aircraft boost channel available without affecting the fixed-wing throttle latch. */
object LegacyPlanePilotKeyConflictContext : VehicleProfileKeyConflictContext(VehicleControlProfile.PLANE) {
    override fun isActive(): Boolean {
        if (!super.isActive()) return false
        val vehicle = Minecraft.getInstance().player?.vehicle as? VehicleEntity ?: return false
        return !vehicle.isFixedWingFlightVehicle()
    }
}

/** Keeps paired ordinary SBW actions inactive while their vehicle profile owns input. */
object NonVehicleKeyConflictContext : IKeyConflictContext {
    override fun isActive(): Boolean =
        KeyConflictContext.IN_GAME.isActive() && !VehicleKeyConflictContext.isActive()

    override fun conflicts(other: IKeyConflictContext): Boolean =
        other === this || other === KeyConflictContext.IN_GAME
}
