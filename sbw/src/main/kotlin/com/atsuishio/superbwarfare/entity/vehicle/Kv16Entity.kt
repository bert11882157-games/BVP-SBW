package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*

class Kv16Entity(type: EntityType<Kv16Entity>, world: Level) : GeoVehicleEntity(type, world) {
    override fun vehicleShootResult(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?): ShotResult {
        val level = living?.level()
        val pos = if (level is ServerLevel) getShootPos(living, 1f) else null
        val result = super.vehicleShootResult(living, uuid, targetPos)
        if (result.isAccepted() && level is ServerLevel && pos != null) {
            ParticleTool.sendParticle(level, ParticleTypes.CLOUD,
                    pos.x,
                    pos.y,
                    pos.z,
                    1, 0.1, 0.1, 0.1, 0.002, true)
        }
        return result
    }
}
