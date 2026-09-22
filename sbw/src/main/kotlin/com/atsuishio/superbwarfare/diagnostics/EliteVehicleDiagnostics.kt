package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.Entity
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

/** Lifecycle and bounded-rate movement observations; no second world/entity scan. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID)
object EliteVehicleDiagnostics {
    private fun relevant(entity: Entity) = entity is VehicleEntity || entity is FastThrowableProjectile

    @SubscribeEvent fun loaded(event: EntityJoinLevelEvent) {
        val entity = event.entity
        if (!EliteDiagnostics.isEnabled(entity.level()) || !relevant(entity)) return
        EliteDiagnostics.record(entity, "lifecycle", "loaded", "position", entity.position(),
            "motion", entity.deltaMovement, "from_disk", event.loadedFromDisk())
    }

    @SubscribeEvent fun unloaded(event: EntityLeaveLevelEvent) {
        val entity = event.entity
        if (!EliteDiagnostics.isEnabled(entity.level()) || !relevant(entity)) return
        EliteDiagnostics.record(entity, "lifecycle", "unloaded", "reason", entity.removalReason,
            "position", entity.position(), "motion", entity.deltaMovement)
    }

    @JvmStatic fun vehicle(vehicle: VehicleEntity) {
        if (!EliteDiagnostics.isEnabled(vehicle.level())) return
        if ((vehicle.tickCount + vehicle.id) % 20 == 0) {
            for ((name, data) in vehicle.gunDataMap) {
                if (!data.get(GunProp.BELT_FED)) continue
                EliteDiagnostics.record(vehicle, "aircraft_belt", "state_sample",
                    "weapon", name, "loaded", data.ammo.get(), "capacity", data.get(GunProp.MAGAZINE),
                    "reserve_synced", data.backupAmmoCount.get(), "remaining_ticks", data.reload.time(),
                    "reload_state", data.reload.state().name, "reload_revision", data.reload.soundCycleRevision(),
                    "belt_phase", data.projectileBeltPhase.get())
            }
        }
        val flight = vehicle.resolveVehicleFlightStrategy()
        if (flight == null && (vehicle.tickCount + vehicle.id) % 5 != 0) return
        EliteDiagnostics.record(vehicle, "vehicle", "movement_health", "position", vehicle.position(),
            "velocity", vehicle.deltaMovement, "speed_blocks_per_second", vehicle.deltaMovement.length() * 20,
            "yaw", vehicle.yRot, "pitch", vehicle.xRot, "roll", vehicle.roll,
            "turret_yaw", vehicle.turretYRot, "turret_pitch", vehicle.turretXRot,
            "health", vehicle.health, "max_health", vehicle.getMaxHealth(), "wreck", vehicle.isWreck,
            "on_ground", vehicle.onGround(), "power", vehicle.power, "engine", vehicle.engineRunning(),
            "passengers", vehicle.passengers.map { it.uuid }.joinToString(","))
        if (flight != null) {
            val instruments = vehicle.getVehicleFlightInstrumentSnapshot(1f)
            EliteDiagnostics.record(vehicle, "flight", "sample", "strategy", flight.javaClass.simpleName,
                "sequence", instruments.sequence, "server_tick", instruments.serverTick,
                "rotor_lift", instruments.rotorLift, "collective", instruments.collective,
                "thrust", instruments.thrust, "throttle", instruments.throttle,
                "motion_includes_gravity", instruments.motionIncludesGravity)
            (flight as? FixedWingFlightStrategy)?.stateSnapshot()?.let { s ->
                EliteDiagnostics.record(vehicle, "flight", "fixed_wing",
                    "profile", s.profileId, "speed_mps", s.speedMps, "aoa_degrees", s.angleOfAttackDegrees,
                    "throttle", s.throttle, "afterburner", s.afterburnerActive, "stall", s.stallActive,
                    "forward_mps", s.forwardVelocityMps, "lateral_mps", s.lateralVelocityMps,
                    "vertical_mps", s.verticalVelocityMps, "pressure_pa", s.dynamicPressurePa,
                    "lift_coefficient", s.liftCoefficient, "drag_coefficient", s.dragCoefficient,
                    "lift_n", s.liftForceNewtons, "drag_n", s.dragForceNewtons, "thrust_n", s.thrustForceNewtons,
                    "control_effectiveness", s.controlEffectiveness, "stall_severity", s.stallSeverity,
                    "yaw_deg_s", s.yawRateDegPerSecond, "pitch_deg_s", s.pitchRateDegPerSecond,
                    "roll_deg_s", s.rollRateDegPerSecond, "mass_kg", s.massKg,
                    "max_speed_mps", s.maxEngineSpeedMps, "stall_speed_mps", s.stallSpeedMps,
                    "stall_aoa", s.stallAoADegrees, "world_speed_envelope_mps", s.worldSpeedEnvelopeMps)
            }
        }
    }

    @JvmStatic fun projectile(projectile: FastThrowableProjectile) {
        if (!EliteDiagnostics.isEnabled(projectile.level()) || projectile.tickCount % 2 != 0) return
        val resolved = ProjectileProfiles.resolve(projectile)
        val guided = projectile as? WireGuideMissileEntity
        EliteDiagnostics.record(projectile, "projectile", "flight", "profile", ProjectileProfiles.profileId(projectile),
            "round", resolved?.combat?.roundId, "munition_type", resolved?.combat?.munitionType,
            "position", projectile.position(), "velocity", projectile.deltaMovement,
            "speed_blocks_per_second", projectile.deltaMovement.length() * 20,
            "owner", projectile.owner?.uuid, "age_ticks", projectile.tickCount,
            "propulsion_phase", guided?.guidedPropulsionPhase(), "relative_speed", guided?.guidedPropulsionSpeed())
    }
}
