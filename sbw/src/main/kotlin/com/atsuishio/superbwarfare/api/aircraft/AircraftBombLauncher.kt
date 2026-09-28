package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.projectile.AerialBombEntity
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

object AircraftBombLauncher {
    /** [launchOffset]: hull offset from [mount] to the release point (the pylon layout's); null uses LaunchOffset. */
    @JvmStatic @JvmOverloads fun launch(vehicle: VehicleEntity, player: ServerPlayer, mount: Vec3, store: JsonObject,
                                      channel: String? = null, launchOffset: Vec3? = null): Boolean {
        val level = vehicle.level() as? ServerLevel ?: return false
        if (player.level() !== level || !vehicle.isAlive) return false
        val config = store.getAsJsonObject("Bomb") ?: return false
        // A TV bomb may be released locked (the seeker starts on the locked target) or unlocked (the pilot then
        // finds the target through the seeker); either way the pilot's seeker view opens after release.
        val tv = config["Mode"]?.asString == "TV"
        val target = if (tv && channel != null && AircraftMissileLauncher.update(vehicle, player, channel, store) == 2) {
            val lock = AircraftMissileLauncher.state(vehicle, channel)
            if (lock.hasUUID("TargetUUID"))
                level.getEntity(lock.getUUID("TargetUUID"))?.takeIf { it.isAlive && !it.isRemoved } else null
        } else null
        config.getAsJsonObject("Cluster")?.let { cluster ->
            val profileKey = when (cluster["Mode"]?.asString) {
                "HEAT" -> "BombletProfile"
                "SENSOR_FUZED" -> "SensorProjectileProfile"
                else -> null
            }
            if (profileKey != null) {
                val id = ResourceLocation.tryParse(cluster[profileKey]?.asString ?: "") ?: return false
                if (ProjectileProfiles.resolve(id)?.combat == null) return false
            }
        }
        val mass = store["MassKg"]?.asDouble ?: return false
        val entity: AerialBombEntity = when {
            mass < 125.0 -> ModEntities.SC_50.get().create(level)
            mass < 1000.0 -> ModEntities.SC_250.get().create(level)
            else -> ModEntities.MK_82.get().create(level)
        } ?: return false
        val offset = launchOffset ?: AircraftArmamentRegistry.vector(store["LaunchOffset"]) ?: Vec3.ZERO
        val local = mount.add(offset)
        val gps = AircraftBombTargeting.gpsTarget(vehicle)
        val origin = AircraftBombFlight.launchOrigin(vehicle, local)
        val motion = AircraftBombFlight.initialMotion(vehicle.deltaMovement)
        if (!origin.x.isFinite() || !origin.y.isFinite() || !origin.z.isFinite() ||
            !motion.x.isFinite() || !motion.y.isFinite() || !motion.z.isFinite()) return false
        entity.owner = player
        entity.setPos(origin.x, origin.y, origin.z)
        entity.deltaMovement = motion
        entity.lifeValue = 600
        entity.configure(config["Mode"].asString, vehicle.uuid, config["Gravity"].asFloat,
            config["DragMultiplier"].asDouble, config["TurnDegreesPerTick"].asDouble,
            config["BlastDamage"].asFloat, config["BlastRadius"].asFloat,
            gps, target?.uuid)
        com.atsuishio.superbwarfare.tools.blast.TntEquivalents.set(entity, config["TntEquivalentKg"]?.asDouble ?: 0.0)
        config.getAsJsonObject("Cluster")?.let { AircraftClusterBomb.configure(entity, it) }
        config.getAsJsonObject("Penetrator")?.let { AircraftBombPenetrator.configure(entity, it) }
        store["ProjectileProfile"]?.asString?.let {
            ProjectileProfiles.assign(entity, ResourceLocation(it))
        }
        if (tv) AircraftTvGuidance.attach(entity, vehicle, player, track = target,
            point = AircraftDesignationData.get(level).get(vehicle.uuid)?.live(level))
        val accepted = level.addFreshEntity(entity)
        if (accepted && tv) AircraftTvGuidance.openView(entity, vehicle, player)
        if (accepted && config["Mode"].asString == "GPS") AircraftBombTargeting.consumeGpsTarget(vehicle, gps)
        if (accepted) AircraftMunitionDebug.log(entity, "bomb release mode=${config["Mode"].asString} massKg=$mass")
        return accepted
    }
}
