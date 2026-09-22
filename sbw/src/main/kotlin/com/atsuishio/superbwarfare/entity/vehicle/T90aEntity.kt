package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.entity.buildControllers
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity
import com.atsuishio.superbwarfare.tools.ParticleTool
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import software.bernie.geckolib.core.animation.AnimatableManager
import java.util.*

class T90aEntity(type: EntityType<T90aEntity>, world: Level) : GeoVehicleEntity(type, world) {

    override fun getDamageModifier() = super.getDamageModifier()
        .custom { source, damage -> getSourceAngle(source, 0.3f) * damage }

    override fun registerControllers(data: AnimatableManager.ControllerRegistrar) = buildControllers(data) {
        "cannon" {
            if (getShootAnimationTimer(0, 0) > 0) {
                thenPlay("animation.t_90a.fire")
            } else {
                thenLoop("animation.t_90a.idle")
            }
        }
        "coax" {
            if (getShootAnimationTimer(0, 1) > 0) {
                thenPlay("animation.t_90a.fire_coax")
            } else {
                thenLoop("animation.t_90a.idle_coax")
            }
        }
        "passengerWeaponStation" {
            if (getShootAnimationTimer(1, 0) > 0) {
                thenPlay("animation.t_90a.fire_weapon_station")
            } else {
                thenLoop("animation.t_90a.idle_weapon_station")
            }
        }
    }

    override fun vehicleShootResult(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?): ShotResult {
        val level = living?.level()
        val emitMuzzle = level is ServerLevel && living == firstPassenger && getWeaponIndex(0) == 0
        val direction = if (emitMuzzle) getShootVec(living, 1f) else null
        val position = if (emitMuzzle) getShootPos(living, 1f) else null
        val result = super.vehicleShootResult(living, uuid, targetPos)
        if (result.isAccepted() && level is ServerLevel && direction != null && position != null) {
            ParticleTool.spawnBigCannonMuzzleParticles(direction, position, level, this)
        }
        return result
    }

    override fun getTurretMaxHealth() = 100f
    override fun getWheelMaxHealth() = 100f
    override fun getEngineMaxHealth() = 150f
}
