package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

object AircraftBombLauncher {
    @JvmStatic fun launch(vehicle: VehicleEntity, player: ServerPlayer, mount: Vec3, store: JsonObject): Boolean {
        val level = vehicle.level() as? ServerLevel ?: return false
        if (player.level() !== level || !vehicle.isAlive) return false
        val config = store.getAsJsonObject("Bomb") ?: return false
        val mass = store["MassKg"]?.asDouble ?: return false
        val entity: AerialBombEntity = when {
            mass < 125.0 -> ModEntities.SC_50.get().create(level)
            mass < 1000.0 -> ModEntities.SC_250.get().create(level)
            else -> ModEntities.MK_82.get().create(level)
        } ?: return false
        val offset = AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO
        val local = mount.add(offset)
        val position = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        val origin = Vec3(position.x, position.y, position.z)
        val motion = vehicle.deltaMovement.add(0.0, -0.04, 0.0)
        if (!origin.x.isFinite() || !origin.y.isFinite() || !origin.z.isFinite() ||
            !motion.x.isFinite() || !motion.y.isFinite() || !motion.z.isFinite()) return false
        entity.owner = player
        entity.setPos(origin.x, origin.y, origin.z)
        entity.deltaMovement = motion
        entity.lifeValue = 600
        entity.configure(config["Mode"].asString, vehicle.uuid, config["Gravity"].asFloat,
            config["DragMultiplier"].asDouble, config["TurnDegreesPerTick"].asDouble,
            config["BlastDamage"].asFloat, config["BlastRadius"].asFloat,
            AircraftBombTargeting.gpsTarget(vehicle))
        val accepted = level.addFreshEntity(entity)
        if (accepted) AircraftMunitionDebug.log(entity, "bomb release mode=${config["Mode"].asString} massKg=$mass")
        return accepted
    }
}
