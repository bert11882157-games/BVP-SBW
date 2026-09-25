package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeProjectileHitDamage
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.atsuishio.superbwarfare.tools.forceHurt
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.Item
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BellBlock
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

open class SmallCannonShellEntity(type: EntityType<out SmallCannonShellEntity>, level: Level) :
    FastThrowableProjectile(type, level) {
    private var aa = false
    override fun farProjectileLookAheadTicks(): Int = if (aa) 1 else 0

    init {
        this.noCulling = true
        this.damageValue = 40f
        this.explosionDamageValue = 80f
        this.explosionRadiusValue = 5f
    }

    override fun getDefaultItem(): Item {
        return ModItems.SMALL_SHELL_AP.get()
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        compound.putBoolean("AA", this.aa)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        compound.readIfPresent("AA") { aa = getBoolean(it) }
    }

    override fun onHitEntity(result: EntityHitResult) {
        super.onHitEntity(result)
        val entity = result.entity
        val owner = this.owner
        if (owner != null && owner.vehicle != null && entity == owner.vehicle) return
        if (this.level() is ServerLevel) {
            entity.forceHurt(causeProjectileHitDamage(this.level().registryAccess(), this, owner), damageValue)

            if (entity is LivingEntity) {
                entity.invulnerableTime = 0
            }

            if (this.tickCount > 0) {
                causeExplode(result.getLocation(), true)
            }
            this.discard()
        }
    }

    public override fun onHitBlock(blockHitResult: BlockHitResult) {
        super.onHitBlock(blockHitResult)
        val resultPos = blockHitResult.blockPos
        val state = this.level().getBlockState(resultPos)

        if (this.level() is ServerLevel) {
            val hardness = this.level().getBlockState(resultPos).block.defaultDestroyTime()
            if (hardness != -1f) {
                if (ExplosionConfig.extraBlockEffects()) {
                    val destroy = Math.random() < (1.0 - (hardness / 50.0)).coerceIn(0.1, 1.0)
                    if (destroy && !ProjectileProfiles.suppressesVehicleBlockDamage(this)) {
                        this.level().destroyBlock(resultPos, true)
                    }
                }
            }
        }

        val block = state.block
        if (block is BellBlock) {
            block.attemptToRing(this.level(), resultPos, blockHitResult.direction)
        }
        if (this.level() is ServerLevel) {
            causeExplode(blockHitResult.getLocation(), false)
        }
        this.discard()
    }

    private fun causeExplode(vec3: Vec3, hitEntity: Boolean) {
        CustomExplosion.Builder(this)
            .attacker(this.owner)
            .damage(explosionDamageValue)
            .radius(explosionRadiusValue)
            .position(vec3)
            .withParticleType(explosionParticleType(explosionRadiusValue))
            .destroyBlock { if (hitEntity) Explosion.BlockInteraction.KEEP else (if (ExplosionConfig.explosionsBreakBlocks()) Explosion.BlockInteraction.DESTROY else Explosion.BlockInteraction.KEEP) }
            .damageMultiplier(1.25f)
            .emitFx(shouldEmitDefaultImpactFx())
            .tntEquivalent(com.atsuishio.superbwarfare.tools.blast.TntEquivalents.resolve(this))
            .explode()
    }

    override fun tick() {
        val sweepStart = position()
        super.tick()
        smallTrail()

        if (aa) {
            crushProjectile(sweepStart, position())
        }
        if (owner != null && distanceToSqr(owner!!) > 1048576) {
            if (level() is ServerLevel) {
                causeExplode(position())
            }
            this.discard()
        }
    }

    fun crushProjectile(velocity: Vec3) {
        val sweepEnd = position()
        crushProjectile(sweepEnd.subtract(velocity), sweepEnd)
    }

    private fun crushProjectile(sweepStart: Vec3, sweepEnd: Vec3) {
        if (this.level() is ServerLevel) {
            val frontBox = boundingBox.inflate(0.5).expandTowards(sweepEnd.subtract(sweepStart))

            val target = level().getEntities(
                EntityTypeTest.forClass(Projectile::class.java),
                frontBox,
            ) { it !== this }
                .filter {
                    it !is SmallCannonShellEntity && when (it) {
                        is WireGuideMissileEntity ->
                            WireGuideMissileEntity.preciseInterceptionHitPoint(it, sweepStart, sweepEnd) != null

                        else -> it.bbWidth >= 0.3 || it.bbHeight >= 0.3
                    }
                }
                .minByOrNull { it.position().distanceTo(this.position()) }

            if (target != null) {
                causeExplode(target.position(), false)
                if (target is DestroyableProjectile) {
                    val owner = this.owner
                    if (owner is LivingEntity) {
                        if (owner is ServerPlayer) {
                            owner.level().playSound(
                                null,
                                owner.blockPosition(),
                                ModSounds.INDICATION.get(),
                                SoundSource.VOICE,
                                1f,
                                1f
                            )
                            sendPacketTo(owner, ClientIndicatorMessage(0, 5))
                        }
                    }
                    target.forceHurt(
                        causeProjectileHitDamage(this.level().registryAccess(), this, owner),
                        damageValue
                    )
                } else {
                    target.discard()
                }

                this.discard()
            }
        }
    }

    fun antiAir(antiAir: Boolean) {
        this.aa = antiAir
    }

    override fun isFastMoving(): Boolean {
        return false
    }

    /** Unguided autocannon rounds: deterministic client flight with sparse step-aligned corrections. */
    override fun smoothsBallisticFlight(): Boolean = motionSyncMode() == MotionSyncMode.ENTITY_INTERVAL
}
