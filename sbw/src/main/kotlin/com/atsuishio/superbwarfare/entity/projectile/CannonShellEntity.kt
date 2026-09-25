package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeProjectileHitDamage
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModMobEffects
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
import com.atsuishio.superbwarfare.tools.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.max

open class CannonShellEntity(type: EntityType<out CannonShellEntity>, level: Level) :
    FastThrowableProjectile(type, level), BasicGeoProjectileEntity {

    private var fireProbability = 0f
    private var fireTime = 0

    enum class Type {
        AP, HE, CM, WP
    }

    private var type: Type? = Type.AP
    override fun farProjectileLookAheadTicks(): Int = when (type) {
        Type.CM, Type.WP -> 8
        Type.AP -> 1 // Native AP also queries one velocity segment beyond the struck entity.
        else -> 0
    }
    private var spreadAmount = 50
    private var spreadAngle = 15

    init {
        this.noCulling = true
    }

    fun durability(durability: Int): CannonShellEntity {
        this.durability = durability
        return this
    }

    override fun isColliding(pPos: BlockPos, pState: BlockState): Boolean {
        return true
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)

        compound.putFloat("FireProbability", this.fireProbability)
        compound.putInt("FireTime", this.fireTime)
        this.type?.let { compound.putString("Type", it.name) }
        compound.putInt("SpreadAmount", this.spreadAmount)
        compound.putInt("SpreadAngle", this.spreadAngle)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)

        compound.readIfPresent("FireProbability") { fireProbability = getFloat(it) }
        compound.readIfPresent("FireTime") { fireTime = getInt(it) }
        compound.readIfPresent("Type") { key ->
            Type.values().firstOrNull { it.name == getString(key) }?.let { this@CannonShellEntity.type = it }
        }
        compound.readIfPresent("SpreadAmount") { spreadAmount = getInt(it) }
        compound.readIfPresent("SpreadAngle") { spreadAngle = getInt(it) }
    }

    override fun getDefaultItem(): Item {
        return ModItems.LARGE_SHELL_HE.get()
    }

    override fun onHitBlock(result: BlockHitResult) {
        super.onHitBlock(result)
        val level = this.level()
        if (level is ServerLevel) {
            if (type == Type.WP) {
                findNearEntity(result.location, owner!!)
                causeExplode(result.location)
                this.discard()
            }
            if (type != Type.AP) {
                causeExplode(result.location)
                this.discard()
            } else {
                val resistance = blockPenetrationResistance(result) ?: return
                if (shouldEmitDefaultImpactFx()) {
                    ParticleTool.cannonHitParticles(level, result.location)
                }
                val cannonShell = CannonShellEntity(ModEntities.CANNON_SHELL.get(), level)
                cannonShell.setPos(result.location.add(deltaMovement.normalize().scale(0.99)))
                cannonShell.shoot(
                    deltaMovement.x,
                    deltaMovement.y,
                    deltaMovement.z,
                    (deltaMovement.length() * resistance).toFloat(),
                    0f
                )
                inheritPenetrationState(cannonShell, resistance) { setType(Type.AP) }
                level.addFreshEntity(cannonShell)

                this.discard()
            }
        }
    }

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        val level = this.level()
        if (level is ServerLevel) {
            val entity = result.entity
            if (this.owner != null && entity == this.owner!!.vehicle) return

            entity.forceHurt(
                causeProjectileHitDamage(this.level().registryAccess(), this, this.owner),
                this.damageValue
            )

            if (entity is LivingEntity) {
                entity.invulnerableTime = 0
            }

            if (type == Type.WP) {
                findNearEntity(result.location, owner!!)
            }


            if (entity is VehicleEntity) {
                causeExplode(result.location)
                this.discard()
            }

            if (type == Type.AP) {
                val pos = entity.boundingBox.center
                val resultEntities = TraceTool.getEntitiesAlongVector(level, pos, deltaMovement) { true }
                var resistance = 1.0

                for (rayTraceResultEntity in resultEntities) {
                    if (rayTraceResultEntity.entity != null) {
                        resistance *= 0.95
                        val target = rayTraceResultEntity.entity
                        if (rayTraceResultEntity.entity !== entity) {
                            target.forceHurt(
                                causeProjectileHitDamage(this.level().registryAccess(), this, this.owner),
                                (this.damageValue * resistance).toFloat()
                            )
                            if (target is LivingEntity) {
                                target.invulnerableTime = 0
                            }
                        }
                    }
                }

                deltaMovement = deltaMovement.scale(resistance)
                setDamage((this.damageValue * resistance).toFloat())
            } else {
                causeExplode(result.location)
                this.discard()
            }
        }
    }

    fun findNearEntity(pos: Vec3, shooter: Entity) {
        if (this.level() !is ServerLevel) {
            return
        }

        val entities = SeekTool.Builder(shooter)
            .withinRange(pos, explosionRadiusValue.toDouble())
            .notItsVehicle()
            .baseFilter()
            .noVehicle()
            .build()

        for (e in entities) {
            val dis = pos.distanceTo(e.position())

            if (e is LivingEntity && checkNoClip(e, pos)) {
                if (e is Player && e.isCreative) {
                    return
                }
                if (!e.level().isClientSide()) {
                    e.addEffect(
                        MobEffectInstance(
                            ModMobEffects.PHOSPHORUS_FIRE.get(),
                            (300 - 30 * dis).toInt(),
                            max(explosionRadiusValue - dis, 0.0).toInt()
                        ), this.owner
                    )
                }
            }
        }
    }

    override fun tick() {
        super.tick()

        mediumTrail()

        if ((type == Type.CM || type == Type.WP) && tickCount > 3) {
            // 使用Minecraft内置的光线追踪进行碰撞检测
            val spreadTime = 8
            val hitResult = level().clip(
                ClipContext(
                    position(),
                    position().add(deltaMovement.scale(spreadTime.toDouble())),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.ANY,
                    this
                )
            )

            if (hitResult.type == HitResult.Type.BLOCK) {
                if (type == Type.CM) {
                    releaseClusterMunitions(owner)
                } else {
                    releaseWp(owner)
                }
            }

            val target = TraceTool.findLookingEntity(this, deltaMovement.scale(spreadTime.toDouble()).length())
            if (target != null && target != this) {
                if (type == Type.CM) {
                    releaseClusterMunitions(owner)
                } else {
                    releaseWp(owner)
                }
            }
        }
    }

    fun releaseClusterMunitions(shooter: Entity?) {
        val level = this.level()
        if (level is ServerLevel) {
            ParticleTool.spawnMediumExplosionParticles(level, position())
            repeat(spreadAmount) {
                val gunGrenadeEntity = GunGrenadeEntity(
                    shooter, level,
                    6 * damageValue / spreadAmount,
                    5 * explosionDamageValue / spreadAmount,
                    explosionRadiusValue / 2
                )

                // Submunitions carry their own (data-table) charge, never the parent's or the gun grenade default.
                com.atsuishio.superbwarfare.tools.blast.TntEquivalents.set(gunGrenadeEntity,
                    com.atsuishio.superbwarfare.tools.blast.TntDefaults.get(com.atsuishio.superbwarfare.tools.blast.TntBlast.CM_SUBMUNITION_KEY) ?: 0.0)
                gunGrenadeEntity.setPos(position().x, position().y, position().z)
                gunGrenadeEntity.shoot(
                    deltaMovement.x,
                    deltaMovement.y,
                    deltaMovement.z,
                    (random.nextFloat() * 0.2f + 0.4f * deltaMovement.length()).toFloat(),
                    spreadAngle.toFloat()
                )
                level.addFreshEntity(gunGrenadeEntity)
            }
            discard()
        }
    }

    private fun releaseWp(shooter: Entity?) {
        val level = this.level()
        if (level is ServerLevel) {
            ParticleTool.spawnMediumExplosionParticles(level, position())
            repeat(spreadAmount) {
                val whitePhosphorusProjectileEntity = WhitePhosphorusProjectileEntity(shooter, level)

                whitePhosphorusProjectileEntity.setPos(position().x, position().y, position().z)
                whitePhosphorusProjectileEntity.shoot(
                    deltaMovement.x,
                    deltaMovement.y,
                    deltaMovement.z,
                    (random.nextFloat() * 0.02f + 0.3f * deltaMovement.length()).toFloat(),
                    spreadAngle.toFloat()
                )
                level.addFreshEntity(whitePhosphorusProjectileEntity)
            }
            discard()
        }
    }

    override fun motionSyncMode(): MotionSyncMode {
        if (!shouldSyncMotion()) return MotionSyncMode.NONE
        return ProjectileProfiles.motionSyncMode(this, MotionSyncMode.EVERY_TICK)
    }

    override fun discardAfterExplode(): Boolean {
        return true
    }

    override fun getSound(): SoundEvent {
        return ModSounds.SHELL_FLY.get()
    }

    override fun getVolume(): Float {
        return 0.07f
    }

    override fun forceLoadChunk(): Boolean {
        return true
    }

    fun getShellType(): Type? = type

    fun setType(type: Type?) {
        this.type = type
    }

    fun setSpreadAmount(spreadAmount: Int) {
        this.spreadAmount = spreadAmount
    }

    fun setSpreadAngle(spreadAngle: Int) {
        this.spreadAngle = spreadAngle
    }

    override fun getModel() = BedrockModelLoader.CANNON_SHELL_MODEL

    override fun getHiddenTicks() = 1
}
