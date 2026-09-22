package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.entity.buildControllers
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.core.animation.AnimatableManager
import java.util.*

class Ju87Entity(type: EntityType<Ju87Entity>, world: Level) : GeoVehicleEntity(type, world) {

    override var turretYRot = 180f
    override var turretYRotO = 180f

    override fun vehicleShootResult(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?): ShotResult {
        val level = living?.level()
        val emitMuzzle = level is ServerLevel && living == firstPassenger && getWeaponIndex(0) == 0
        val pos = if (emitMuzzle) getShootPos(living, 1f) else null
        val result = super.vehicleShootResult(living, uuid, targetPos)
        if (result.isAccepted() && level is ServerLevel && pos != null) {
            ParticleTool.sendParticle(level, ParticleTypes.CLOUD,
                    pos.x,
                    pos.y,
                    pos.z,
                    1, 0.1, 0.1, 0.1, 0.0, true)
        }
        return result
    }

    override fun registerControllers(data: AnimatableManager.ControllerRegistrar) = buildControllers(data) {
        "machineGun" {
            if (getShootAnimationTimer(1, 0) > 0) {
                thenPlay("animation.mg_17.fire")
            } else {
                thenLoop("animation.mg_17.idle")
            }
        }
    }
}
