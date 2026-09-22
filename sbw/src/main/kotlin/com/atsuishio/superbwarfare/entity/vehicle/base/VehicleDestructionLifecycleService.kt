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

    fun tickAfterVanilla() {
        if (vehicle.level() is ServerLevel && vehicle.health <= 0 && !vehicle.isWreck) {
            // Preserve the legacy pre-dispatch wreck marker for destroy() overrides.
            vehicle.isWreck = true
            vehicle.destroy()
        }

        if (!vehicle.isWreck) return

        val aircraft = vehicle.vehicleType == VehicleType.AIRPLANE ||
            vehicle.vehicleType == VehicleType.HELICOPTER
        if (aircraft && (vehicle.onGround() || vehicle.isInFluidType) && !vehicle.sympatheticDetonated) {
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

        if (vehicle.health <= -vehicle.getMaxHealth()) {
            vehicle.discard()
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
        if (vehicle.hasTurret() && ejectTurret && !vehicle.sympatheticDetonated) {
            spawnTurretWreck(destroyInfo, context)
        }

        if (destroyInfo.noWreck) {
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

            context.particlePosition()?.let(explosion::particlePosition)
            if (!destroyInfo.explodeBlocks) {
                explosion.keepBlock()
            }
            explosion.explode()
        }
    }
}
