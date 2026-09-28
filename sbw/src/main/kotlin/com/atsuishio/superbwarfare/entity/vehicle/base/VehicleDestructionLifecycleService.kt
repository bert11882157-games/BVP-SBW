package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.Mod.Companion.queueServerWork
import com.atsuishio.superbwarfare.api.vehicle.destruction.TurretEjectionPolicy
import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContext
import com.atsuishio.superbwarfare.data.vehicle.subdata.DestroyInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.TurretWreckEntity
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getYRotFromVector
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.tools.ParticleTool
import com.atsuishio.superbwarfare.tools.VectorTool.combineRotationsTurret
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.minecraftforge.registries.ForgeRegistries

/** Owns the destruction and wreck transaction behind VehicleEntity's compatibility facade. */
internal class VehicleDestructionLifecycleService(
    private val vehicle: VehicleEntity,
) {
    var activeContext: VehicleDestructionContext? = null
    private var farDeathPublished = false
    private val aircraft: Boolean get() = vehicle.vehicleType == VehicleType.AIRPLANE ||
        vehicle.vehicleType == VehicleType.HELICOPTER

    private fun publishFarDeath() {
        if (farDeathPublished || vehicle.level().isClientSide) return
        farDeathPublished = true
        if (vehicle.aircraftWreckStart < 0L) vehicle.aircraftWreckStart = vehicle.level().gameTime
        if (aircraft) com.atsuishio.superbwarfare.api.aircraft.AircraftCombatEffects.aircraftBreakup(vehicle)
        // A vehicle with a TNT death charge shows the fireball only (no white SBW burst rings).
        else if (vehicle.computed().destroyInfo.deathBurst && vehicle.computed().deathChargeKg <= 0.0)
            com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage.sendFarDeath(vehicle)
    }

    fun tickAfterVanilla() {
        if (!vehicle.level().isClientSide && !vehicle.isWreck &&
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle) != 0 &&
            (vehicle.isInFluidType || (vehicle.hasRecentFixedWingWorldContact() &&
                vehicle.level().gameTime - vehicle.aircraftWreckStart >= 10))) {
            vehicle.crash = true
            val source = com.atsuishio.superbwarfare.init.ModDamageTypes.causeVehicleStrikeDamage(
                vehicle.level().registryAccess(), vehicle, vehicle.lastDriver ?: vehicle)
            vehicle.applyResolvedDamage(com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                source, vehicle.getMaxHealth(), modulePolicy =
                    com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy.SKIP_NATIVE, lethal = true))
        }
        if (vehicle.level() is ServerLevel && vehicle.health <= 0 && !vehicle.isWreck) {
            publishFarDeath()
            // Preserve the legacy pre-dispatch wreck marker for destroy() overrides.
            vehicle.isWreck = true
            vehicle.destroy()
        }

        if (!vehicle.isWreck) return
        if (!vehicle.level().isClientSide && vehicle.aircraftWreckStart < 0L)
            vehicle.aircraftWreckStart = vehicle.level().gameTime
        val ownLifetime = vehicle.computed().destroyInfo.wreckLifetimeTicks
        if (!vehicle.level().isClientSide && vehicle.level().gameTime - vehicle.aircraftWreckStart >=
            (if (ownLifetime > 0 && !aircraft) ownLifetime.toLong()
                else com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics.WRECK_LIFETIME_TICKS.toLong())) {
            vehicle.discard()
            vehicle.generateWreckageLoot()
            return
        }
        if (aircraft) com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.update(vehicle)

        val aircraft = vehicle.vehicleType == VehicleType.AIRPLANE ||
            vehicle.vehicleType == VehicleType.HELICOPTER
        // Keep the later fix: an airborne wreck hitting a wall must also detonate.
        val destructiveFixedWingContact = vehicle.hasRecentFixedWingWorldContact()
        if (aircraft && (vehicle.onGround() || vehicle.isInFluidType || destructiveFixedWingContact || vehicle.aircraftWreckImpactTime >= 0)
            && !vehicle.sympatheticDetonated) {
            vehicle.sympatheticDetonated = true
            val destroyInfo = vehicle.computed().destroyInfo
            if (destroyInfo.explodePassengers) {
                if (vehicle.crash && destroyInfo.crashPassengers) {
                    vehicle.invokeCrashPassengers()
                } else {
                    vehicle.invokeExplodePassengers()
                }
            }
            vehicle.vehicleExplosion(destroyInfo)
            vehicle.ejectPassengers()
        }

        if (vehicle.health <= -vehicle.getMaxHealth() && (!aircraft || vehicle.sympatheticDetonated)) {
            vehicle.discard()
            if (!vehicle.computed().destroyInfo.deathBurst) {
                vehicle.generateWreckageLoot()
                return
            }
            vehicle.createCustomExplosion()
                .radius(0f)
                .damage(0f)
                .withParticleType(ParticleTool.ParticleType.SMALL)
                .keepBlock()
                .explode()
            vehicle.generateWreckageLoot()
        }

        if (!aircraft) {
            vehicle.ejectPassengers()
        }
    }

    fun destroyBase() {
        val resolvedContext = activeContext ?: vehicle.defaultDestructionContext()
        activeContext = resolvedContext
        performDestruction(resolvedContext)
    }

    fun destroy(context: VehicleDestructionContext) {
        publishFarDeath()
        activeContext = context
        // Keep the virtual zero-argument entry in the dispatch chain for native and addon
        // overrides; the wreck bit must be visible before their first instruction.
        vehicle.isWreck = true
        vehicle.destroy()
    }

    fun vehicleExplosion(destroyInfo: DestroyInfo) {
        val context = activeContext ?: vehicle.defaultDestructionContext()
        performVehicleExplosion(destroyInfo, context)
    }

    fun vehicleExplosion(destroyInfo: DestroyInfo, context: VehicleDestructionContext) {
        activeContext = context
        performVehicleExplosion(destroyInfo, context)
    }

    private fun performDestruction(context: VehicleDestructionContext) {
        publishFarDeath()
        vehicle.isWreck = true
        val destroyInfo = vehicle.computed().destroyInfo

        if (vehicle.vehicleType != VehicleType.AIRPLANE && vehicle.vehicleType != VehicleType.HELICOPTER) {
            if (destroyInfo.explodePassengers) {
                if (vehicle.crash && destroyInfo.crashPassengers) {
                    vehicle.invokeCrashPassengers()
                } else {
                    vehicle.invokeExplodePassengers()
                }
            }
            vehicle.vehicleExplosion(destroyInfo)
        }

        val ejectTurret = when (context.turretPolicy()) {
            TurretEjectionPolicy.DEFAULT -> destroyInfo.sympatheticDetonation &&
                Math.random() < destroyInfo.sympatheticDetonationChance
            TurretEjectionPolicy.KEEP_ATTACHED -> false
            TurretEjectionPolicy.FORCE_EJECT -> true
        }
        if (vehicle.hasTurret() && vehicle.allowsTurretEjection() && ejectTurret && !vehicle.sympatheticDetonated) {
            spawnTurretWreck(destroyInfo, context)
        }

        if (destroyInfo.noWreck && !aircraft) {
            vehicle.discard()
        }
    }

    private fun spawnTurretWreck(destroyInfo: DestroyInfo, context: VehicleDestructionContext) {
        vehicle.sympatheticDetonated = true
        val turretWreckEntity = TurretWreckEntity(ModEntities.TURRET_WRECK.get(), vehicle.level())
        val turretPosition = vehicle.turretPos
        if (turretPosition != null) {
            val position = vehicle.position().add(turretPosition)
            turretWreckEntity.setPos(position.x, position.y, position.z)
        } else {
            turretWreckEntity.setPos(vehicle.x, vehicle.eyeY, vehicle.z)
        }

        val nativeDirection = vehicle.getUpVec(1f).add(
            vehicle.deltaMovement.add(Vec3(0.0, vehicle.computed().gravity, 0.0))
        )
        val nativeRandomScale = (Math.random() - 0.5) * 0.4 + 1
        turretWreckEntity.deltaMovement = context.turretImpulse() ?: Vec3(
            nativeDirection.x,
            nativeDirection.y,
            nativeDirection.z,
        ).normalize().add(
            vehicle.sampleTurretWreckImpulseNoise(),
            vehicle.sampleTurretWreckImpulseNoise(),
            vehicle.sampleTurretWreckImpulseNoise(),
        ).scale(destroyInfo.sympatheticDetonationForce.toDouble() * nativeRandomScale)

        val quaternion = combineRotationsTurret(1f, vehicle)
        val vehicleId = ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type)
        turretWreckEntity.vehicleName = vehicleId.toString()
        turretWreckEntity.wreckVisualId = (context.wreckVisualId() ?: vehicleId).toString()
        turretWreckEntity.crushPolicyId = context.turretCrushPolicyId()?.toString() ?: ""
        turretWreckEntity.xRot = vehicle.getTurretPitch(1f)
        turretWreckEntity.yRot = -getYRotFromVector(vehicle.getBarrelVector(1f)).toFloat()
        turretWreckEntity.setQuaternion0(quaternion)
        turretWreckEntity.setQuaternion(quaternion)
        vehicle.level().addFreshEntity(turretWreckEntity)
    }

    private fun performVehicleExplosion(destroyInfo: DestroyInfo, context: VehicleDestructionContext) {
        val radius = destroyInfo.explosionRadius
        val charge = vehicle.computed().deathChargeKg
        if (charge > 0.0 && com.atsuishio.superbwarfare.tools.blast.TntBlast.active(charge)) {
            // Classed vehicles go up on the TNT model: fuel and stowed ammunition (DeathChargeKg), doubled when the
            // ammunition rack set it off, with the TNT fireball and no SBW burst recipe.
            val kg = charge * (if (context.explosionCauseId()?.path == "ammo_rack") 2.0 else 1.0)
            queueServerWork(1) {
                val explosion = vehicle.createCustomExplosion(context)
                    .radius(0f)
                    .damage(0f)
                    .tntEquivalent(kg)
                    .withParticleType(ParticleTool.ParticleType.MINI)
                    .emitFx(context.emitExplosionFx())
                    .explosionCause(context.explosionCauseId())
                    .explosionProfile(context.explosionProfileId())
                    .keepBlock()
                context.particlePosition()?.let(explosion::particlePosition)
                com.atsuishio.superbwarfare.Mod.LOGGER.info("[Blast Damage] death explosion vehicle={} tnt_kg={} cause={}",
                    ForgeRegistries.ENTITY_TYPES.getKey(vehicle.type), kg, context.explosionCauseId())
                explosion.explode()
            }
            return
        }
        if (radius <= 0) return

        // Capture this transaction's context before queueing; a later damage transaction must not
        // redirect the already-scheduled explosion.
        queueServerWork(1) {
            val explosion = vehicle.createCustomExplosion(context)
                .radius(radius)
                .damage(destroyInfo.explosionDamage)
                .withParticleType(destroyInfo.particleType)
                .emitFx(context.emitExplosionFx())
                .explosionCause(context.explosionCauseId())
                .explosionProfile(context.explosionProfileId())
                // Vehicle destruction never edits terrain, regardless of authored/global blast settings.
                .keepBlock()

            context.particlePosition()?.let(explosion::particlePosition)
            explosion.explode()
        }
    }
}
