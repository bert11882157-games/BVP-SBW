package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.compat.SoundBarrierCompat
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.SonicBoomMessage
import com.atsuishio.superbwarfare.network.message.receive.SoundClientMessage
import com.atsuishio.superbwarfare.tools.SoundTool
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.registries.ForgeRegistries
import java.util.UUID

/** One authoritative speed crossing, independent of native entity tracking distance. */
internal object FixedWingSonicBoom {
    fun emit(vehicle: VehicleEntity) {
        val level = vehicle.level() as? ServerLevel ?: return
        val center = vehicle.position().add(0.0, vehicle.bbHeight * .5, 0.0)
        val nativeBursts = SoundBarrierCompat.particles(vehicle)
        com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.record(vehicle, "fixed_wing", "SONIC_EFFECT",
            "provider", if (nativeBursts.isEmpty()) "sbw_fallback" else "supersonic",
            "bursts", nativeBursts.size, "particles", nativeBursts.sumOf { maxOf(1, it.count) },
            "threshold_kmh", FixedWingSonicCrossing.BOOM_KMH)
        if (nativeBursts.isEmpty()) SoundTool.playDistantSound(level, ModSounds.SONIC_BOOM.get(), center,
            128f, .85f, null, vehicle, null, "SONIC_BOOM")
        val message = SonicBoomMessage(center, Vec3.directionFromRotation(vehicle.xRot, vehicle.yRot),
            (vehicle.bbWidth * .65f).coerceIn(1.5f,8f), nativeBursts)
        for (player in level.players()) if (player.distanceToSqr(center) <= 2048.0 * 2048.0) {
            sendPacketTo(player, message)
            if (nativeBursts.isNotEmpty()) {
                val distance = player.distanceToSqr(center)
                val variant = if (distance < 200.0 * 200.0) "close" else if (distance <= 700.0 * 700.0) "medium" else "far"
                val sound = ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation("supersonic", "sonic_boom_$variant"))
                    ?: ModSounds.SONIC_BOOM.get()
                sendPacketTo(player, SoundClientMessage(sound.location, center.x, center.y, center.z,
                    128f, 1f, UUID.randomUUID(), vehicle.uuid, null, "SONIC_BOOM"))
            }
        }
    }
}
