package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingSpeedEffects
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ViewportEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import kotlin.math.sin

/** Camera-only vibration. Never writes body attitude, player aim or network input. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FixedWingSpeedBuffet {
    @SubscribeEvent
    fun diagnostics(event: net.minecraftforge.event.TickEvent.ClientTickEvent) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END ||
            !com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.isClientEnabled()) return
        val vehicle = Minecraft.getInstance().player?.vehicle as? VehicleEntity ?: return
        if (!vehicle.isFixedWingFlightVehicle() || vehicle.level().gameTime % 2L != 0L) return
        val snapshot = vehicle.getVehicleFlightPresentationSnapshot(1f) ?: return
        com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.record(vehicle, "fixed_wing", "SPEED_PRESENTED",
            "snapshot_tick", snapshot.serverTick, "motion_kmh", snapshot.motion.length() * 72.0,
            "positional_kmh", vehicle.absoluteSpeed * 72.0, "x", vehicle.x, "y", vehicle.y, "z", vehicle.z)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun camera(event: ViewportEvent.ComputeCameraAngles) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val vehicle = player.vehicle as? VehicleEntity ?: return
        if (mc.isPaused || mc.screen != null || vehicle.isWreck || vehicle.onGround() ||
            vehicle.getNthEntity(0) !== player || AircraftArmamentClient.isPodActive(vehicle) ||
            VehicleFreeCameraController.hasPresentation(player, vehicle)) return
        val strategy = vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy ?: return
        val partial = event.partialTick.toFloat()
        val snapshot = vehicle.getVehicleFlightPresentationSnapshot(partial) ?: return
        val amplitude = FixedWingSpeedEffects.buffetDegrees(snapshot.motion.length() * 20.0, strategy.handling)
        val time = (vehicle.level().gameTime + event.partialTick) / 20.0
        event.pitch += (amplitude * (0.65 * sin(time * 43.0) + 0.35 * sin(time * 71.0))).toFloat()
        event.yaw += (amplitude * 0.45 * sin(time * 53.0)).toFloat()
        event.roll += (amplitude * 0.65 * sin(time * 37.0)).toFloat()
    }
}
