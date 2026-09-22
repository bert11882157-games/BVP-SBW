package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/** Server crossing produces one shared, non-damaging sound and vapor cone. */
internal object FixedWingSonicBoom {
    fun emit(vehicle: VehicleEntity) {
        val level = vehicle.level() as? ServerLevel ?: return
        level.playSound(null, vehicle.x, vehicle.y, vehicle.z, ModSounds.EXPLOSION_AIR.get(),
            SoundSource.NEUTRAL, 8f, 0.85f)
        val forward = Vec3.directionFromRotation(vehicle.xRot, vehicle.yRot)
        val side = if (kotlin.math.abs(forward.y) < 0.95) forward.cross(Vec3(0.0, 1.0, 0.0)).normalize()
            else Vec3(1.0, 0.0, 0.0)
        val up = side.cross(forward).normalize()
        val size = (vehicle.bbWidth * 0.65).coerceIn(1.5, 8.0)
        val center = vehicle.position().add(0.0, vehicle.bbHeight * 0.5, 0.0)
        val viewers = level.players().filter { it.distanceToSqr(vehicle) <= 192.0 * 192.0 }
        // A denser, slightly wider shell stays visible between the individual cloud sprites.
        // The burst remains bounded to one crossing; rings share the same axial extent.
        for (ring in 1..6) for (i in 0 until 96) {
            val depth = ring * 4.0 / 6.0
            val angle = (i + (ring % 2) * 0.5) * 2.0 * Math.PI / 96
            val radial = side.scale(cos(angle)).add(up.scale(sin(angle)))
            val point = center.add(forward.scale(-depth * size * 0.35))
                .add(radial.scale(size * depth * 0.45))
            for (player in viewers) level.sendParticles(player, ParticleTypes.CLOUD, true,
                point.x, point.y, point.z, 0, radial.x, radial.y, radial.z, 0.18)
        }
    }
}
