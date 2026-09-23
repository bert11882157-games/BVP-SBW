package com.atsuishio.superbwarfare.tools

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.GroundVehicleBlastPolicy
import com.atsuishio.superbwarfare.api.projectile.HeavyWarheadBlastPolicy
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage.Companion.sendToNearbyPlayers
import com.atsuishio.superbwarfare.tools.DamageHandler.doDamage
import com.mojang.datafixers.util.Pair
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.ProtectionEnchantment
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.ExplosionDamageCalculator
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraftforge.event.ForgeEventFactory
import java.util.function.Supplier
import kotlin.math.floor
import kotlin.math.sqrt

open class CustomExplosion(
    private val level: Level,
    private val pSource: Entity?,
    source: DamageSource?,
    pDamageCalculator: ExplosionDamageCalculator?,
    private val damage: Float,
    private val x: Double,
    private val y: Double,
    private val z: Double,
    private val radius: Float,
    pBlockInteraction: BlockInteraction
) : Explosion(
    level,
    pSource, source, null,
    x,
    y,
    z,
    radius, false, pBlockInteraction
) {
    private val damageSource: DamageSource
    private val damageCalculator: ExplosionDamageCalculator
    private var fireTime = 0
    private var damageMultiplier = 1f
    private var activeGroundVehicleBlast: GroundVehicleBlastPolicy? = null
    private var activeHeavyWarheadBlast: HeavyWarheadBlastPolicy? = null

    /** The Forge vehicle listener leaves these targets to this explosion's resolved damage pass. */
    fun ownsGroundVehicleBlast(entity: VehicleEntity): Boolean =
        activeHeavyWarheadBlast != null || activeGroundVehicleBlast?.appliesTo(entity.vehicleType) == true

    /** The legacy Forge vehicle listener must not turn a visual-only blast radius into damage. */
    fun hasLegacyVehicleBlastDamage(): Boolean = damage.isFinite() && damage > 0f

    init {
        this.damageSource = source ?: level.damageSources().explosion(this)
        this.damageCalculator = pDamageCalculator ?: ExplosionDamageCalculator()
    }

    constructor(
        pLevel: Level,
        pSource: Entity?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float,
        pBlockInteraction: BlockInteraction
    ) : this(pLevel, pSource, null, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, pBlockInteraction)

    constructor(
        pLevel: Level,
        pSource: Entity?,
        source: DamageSource?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float,
        pBlockInteraction: BlockInteraction
    ) : this(pLevel, pSource, source, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, pBlockInteraction) {
        sendToNearbyPlayers(
            level,
            pToBlowX,
            pToBlowY,
            pToBlowZ,
            (4 * radius).toDouble(),
            20 + 0.2 * radius,
            50 + 0.5 * radius
        )
    }

    constructor(
        pLevel: Level,
        pSource: Entity?,
        source: DamageSource?,
        damage: Float,
        pToBlowX: Double,
        pToBlowY: Double,
        pToBlowZ: Double,
        pRadius: Float
    ) : this(pLevel, pSource, source, null, damage, pToBlowX, pToBlowY, pToBlowZ, pRadius, BlockInteraction.KEEP) {
        sendToNearbyPlayers(level, pToBlowX, pToBlowY, pToBlowZ, radius.toDouble(), 5 + 0.2 * radius, 2 + 0.02 * radius)
    }

    fun setFireTime(fireTime: Int): CustomExplosion {
        this.fireTime = fireTime
        return this
    }

    fun setDamageMultiplier(damageMultiplier: Float): CustomExplosion {
        this.damageMultiplier = damageMultiplier
        return this
    }

    override fun explode() {
        if (ExplosionConfig.EXPLOSION_DESTROY.get()) {
            this.level.gameEvent(this.pSource, GameEvent.EXPLODE, Vec3(this.x, this.y, this.z))
            val set = hashSetOf<BlockPos>()

            val center = Vec3(this.x, this.y, this.z)
            val random = level.random

            val aabb = AABB(
                x - 0.5 * radius,
                y - 0.5 * radius,
                z - 0.5 * radius,
                x + 0.5 * radius,
                y + 0.5 * radius,
                z + 0.5 * radius
            )

            val minPos = BlockPos(
                floor(aabb.minX).toInt(),
                floor(aabb.minY).toInt(),
                floor(aabb.minZ).toInt()
            )

            val maxPos = BlockPos(
                floor(aabb.maxX).toInt(),
                floor(aabb.maxY).toInt(),
                floor(aabb.maxZ).toInt()
            )

            BlockPos.betweenClosedStream(minPos, maxPos).forEach { blockpos ->
                var effectiveRadius = 0.4 * radius
                val distanceSqr = blockpos.center.distanceToSqr(center).toFloat()
                var force = this.radius * (0.25f + random.nextFloat() * 0.15f) * 0.02f * damage

                if (distanceSqr > radius * radius * 0.15) {
                    effectiveRadius += (random.nextDouble() - 0.5) * radius * 0.2
                }
                if (level.isInWorldBounds(blockpos) &&
                    blockpos.center.distanceToSqr(center) <= effectiveRadius * effectiveRadius
                ) {
                    val blockState = this.level.getBlockState(blockpos)
                    var resistance = blockState.block.defaultDestroyTime()
                    if (blockState.soundType === SoundType.METAL || blockState.soundType === SoundType.COPPER || blockState.soundType === SoundType.NETHERITE_BLOCK) {
                        resistance *= 3f
                    }
                    force *= (1 - (distanceSqr / (effectiveRadius * effectiveRadius))).toFloat()

                    if (resistance != -1f && force > resistance && this.damageCalculator.shouldBlockExplode(
                            this,
                            this.level,
                            blockpos,
                            blockState,
                            force
                        )
                    ) {
                        set.add(blockpos.immutable())
                    }
                }
            }

            this.toBlow.addAll(set)
        }

        val diagnosticSubject = pSource ?: damageSource.directEntity ?: damageSource.entity
        val diagnostics = diagnosticSubject != null && EliteDiagnostics.isEnabled(level)
        val blastProfile = pSource?.let(ProjectileProfiles::resolve)
        var policyFailure: Throwable? = null
        val vehicleBlast = if (diagnostics) runCatching { GroundVehicleBlastPolicy.parse(blastProfile) }
            .onFailure { policyFailure = it }.getOrNull() else GroundVehicleBlastPolicy.from(blastProfile)
        activeGroundVehicleBlast = vehicleBlast
        val heavyBlast = HeavyWarheadBlastPolicy.from(blastProfile)
        activeHeavyWarheadBlast = heavyBlast
        if (diagnostics) EliteDiagnostics.record(diagnosticSubject!!, "ground_vehicle_blast", "POLICY",
            "source_uuid", pSource?.uuid, "damage_direct_uuid", damageSource.directEntity?.uuid,
            "attacker_uuid", damageSource.entity?.uuid, "profile", blastProfile?.id,
            "extension", blastProfile?.extension(GroundVehicleBlastPolicy.ID)?.toString()?.take(1024),
            "policy_resolved", vehicleBlast != null, "inner", vehicleBlast?.innerRadius,
            "outer", vehicleBlast?.outerRadius, "edge_damage", vehicleBlast?.edgeDamage,
            "parser_exception", policyFailure?.javaClass?.name,
            "parser_message", policyFailure?.message?.take(512),
            "raw_damage", damage, "raw_radius", radius, "position", Vec3(x, y, z))
        val fragmentPolicy = com.atsuishio.superbwarfare.api.projectile.WarheadFragmentPolicy.from(blastProfile)
        val diameter = maxOf(this.radius * 2, vehicleBlast?.outerRadius?.toFloat() ?: 0f, heavyBlast?.outerRadius?.toFloat() ?: 0f)
        val queryRadius = maxOf(diameter.toDouble(), fragmentPolicy?.range ?: 0.0)
        val x0 = Mth.floor(this.x - queryRadius - 1)
        val x1 = Mth.floor(this.x + queryRadius + 1)
        val y0 = Mth.floor(this.y - queryRadius - 1)
        val y1 = Mth.floor(this.y + queryRadius + 1)
        val z0 = Mth.floor(this.z - queryRadius - 1)
        val z1 = Mth.floor(this.z + queryRadius + 1)
        val list = this.level.getEntities(
            this.pSource,
            AABB(x0.toDouble(), y0.toDouble(), z0.toDouble(), x1.toDouble(), y1.toDouble(), z1.toDouble())
        )
        val candidatesBeforeEvent = if (diagnostics) list.size else 0
        ForgeEventFactory.onExplosionDetonate(this.level, this, list, queryRadius)
        val position = Vec3(this.x, this.y, this.z)
        if (diagnostics) EliteDiagnostics.record(diagnosticSubject!!, "ground_vehicle_blast", "QUERY",
            "source_uuid", pSource?.uuid, "profile", blastProfile?.id, "query_radius", diameter,
            "candidates_before_event", candidatesBeforeEvent, "candidates_after_event", list.size)

        var hit = if (fragmentPolicy != null && level is net.minecraft.server.level.ServerLevel)
            com.atsuishio.superbwarfare.api.projectile.WarheadFragments.apply(level, pSource, damageSource, position, list, fragmentPolicy) {
                it is VehicleEntity && heavyBlast?.affects(sqrt(it.boundingBox.distanceToSqr(position))) == true
            } else false

        for (entity in list) {
            val ignoresExplosion = entity.ignoreExplosion()
            if (diagnostics && entity is VehicleEntity) {
                val bounds = entity.boundingBox
                val nearest = Vec3(position.x.coerceIn(bounds.minX, bounds.maxX),
                    position.y.coerceIn(bounds.minY, bounds.maxY), position.z.coerceIn(bounds.minZ, bounds.maxZ))
                EliteDiagnostics.record(entity,
                "ground_vehicle_blast", "TARGET_ROUTE", "source_uuid", pSource?.uuid,
                "profile", blastProfile?.id, "policy_resolved", vehicleBlast != null,
                "vehicle_type", entity.vehicleType, "bounds", bounds,
                "nearest", nearest, "distance", nearest.distanceTo(position),
                "route", when {
                    ignoresExplosion -> "IGNORES_EXPLOSION"
                    vehicleBlast == null -> "NATIVE_NO_POLICY"
                    entity.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AIRPLANE ||
                        entity.vehicleType == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.HELICOPTER -> "NATIVE_AIRCRAFT"
                    else -> "RESOLVED_GROUND"
                })
            }
            if (!ignoresExplosion) {
                if (heavyBlast != null && entity is VehicleEntity) {
                    val distance = sqrt(entity.boundingBox.distanceToSqr(position))
                    val resolved = heavyBlast.damage(distance, entity.vehicleType, entity.isLightlyArmored(), entity.health, entity.getMaxHealth())
                    if (resolved > 0) {
                        val result = entity.applyResolvedDamage(com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                            damageSource, resolved, lethal = heavyBlast.lethal(distance, entity.vehicleType)))
                        hit = hit || result.accepted
                    }
                    continue
                }
                if (vehicleBlast != null && entity is VehicleEntity && vehicleBlast.appliesTo(entity.vehicleType)) {
                    val box = entity.boundingBox
                    val nearest = Vec3(position.x.coerceIn(box.minX, box.maxX),
                        position.y.coerceIn(box.minY, box.maxY), position.z.coerceIn(box.minZ, box.maxZ))
                    val distance = nearest.distanceTo(position)
                    val lethal = vehicleBlast.lethal(distance)
                    val resolved = if (lethal) maxOf(entity.health, entity.getMaxHealth()) else vehicleBlast.damage(distance)
                    if (resolved > 0) {
                        val healthBefore = entity.health
                        val result = entity.applyResolvedDamage(
                            com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest(
                                damageSource, resolved, lethal = lethal))
                        hit = hit || result.accepted
                        if (diagnostics) EliteDiagnostics.record(entity, "ground_vehicle_blast", "RESOLVED_RESULT",
                            "source_uuid", pSource?.uuid, "profile", blastProfile?.id, "nearest", nearest,
                            "distance", distance, "lethal", lethal, "resolved_damage", resolved,
                            "health_before", healthBefore, "health_after", entity.health,
                            "accepted", result.accepted, "applied_damage", result.appliedDamage,
                            "destroyed", result.destroyed, "rejection", result.rejection)
                    } else if (diagnostics) {
                        EliteDiagnostics.record(entity, "ground_vehicle_blast", "ZERO_OUTSIDE_RADIUS",
                            "source_uuid", pSource?.uuid, "profile", blastProfile?.id,
                            "nearest", nearest, "distance", distance, "resolved_damage", resolved)
                    }
                    continue
                }
                val distanceRate = sqrt(entity.distanceToSqr(position)) / diameter.toDouble()
                if (distanceRate <= 1) {
                    val xDistance = entity.x - this.x
                    val yDistance = (if (entity is PrimedTnt) entity.y else entity.eyeY) - this.y
                    val zDistance = entity.z - this.z
                    val distance = sqrt(xDistance * xDistance + yDistance * yDistance + zDistance * zDistance)

                    if (distance != 0.0) {
                        val seenPercent =
                            getSeenPercent(position, entity).toDouble().coerceIn(
                                0.01 * ExplosionConfig.EXPLOSION_PENETRATION_RATIO.get(),
                                Double.POSITIVE_INFINITY
                            )
                        val damagePercent = (1 - distanceRate) * seenPercent
                        val damageFinal = (damagePercent * damagePercent + damagePercent) / 2 * damage
                        if (diagnostics && entity is VehicleEntity) EliteDiagnostics.record(entity,
                            "ground_vehicle_blast", "NATIVE_DAMAGE", "source_uuid", pSource?.uuid,
                            "profile", blastProfile?.id, "distance_rate", distanceRate,
                            "seen_percent", seenPercent, "raw_damage", damage, "requested_damage", damageFinal)

                        if (entity is Monster) {
                            doDamage(
                                entity,
                                this.damageSource,
                                damageFinal.toFloat() * (1 + 0.2f * this.damageMultiplier)
                            )
                        } else {
                            doDamage(entity, this.damageSource, damageFinal.toFloat())
                        }

                        if (entity is LivingEntity) {
                            var force = damageFinal * 0.015
                            force = ProtectionEnchantment.getExplosionKnockbackAfterDampener(entity, force)
                            val vec31 = position.vectorTo(entity.boundingBox.center).normalize()
                            if (entity is Player && !entity.isCreative && !entity.isSpectator) {
                                entity.deltaMovement = entity.deltaMovement.add(vec31.scale(force))
                            } else {
                                entity.deltaMovement = entity.deltaMovement.add(vec31.scale(force))
                            }
                        }

                        if (entity is LivingEntity || entity is VehicleEntity) {
                            hit = true
                        }

                        entity.invulnerableTime = 1

                        if (fireTime > 0) {
                            entity.setSecondsOnFire(fireTime)
                        }
                    }
                }
            }
        }

        if (hit) {
            val player = this.damageSource.entity
            if (player is ServerPlayer) {
                SoundTool.playLocalSound(player, ModSounds.INDICATION.get())
                player.sendPacket(ClientIndicatorMessage(0, 5))
            }
        }
    }

    override fun finalizeExplosion(pSpawnParticles: Boolean) {
        if (this.level.isClientSide) {
            this.level.playLocalSound(
                this.x,
                this.y,
                this.z,
                SoundEvents.GENERIC_EXPLODE,
                SoundSource.BLOCKS,
                4.0f,
                (1.0f + (this.level.random.nextFloat() - this.level.random.nextFloat()) * 0.2f) * 0.7f,
                false
            )
        }

        val flag = this.interactsWithBlocks()
        if (pSpawnParticles) {
            if (!(this.radius < 2.0f) && flag) {
                this.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, this.x, this.y, this.z, 1.0, 0.0, 0.0)
            } else {
                this.level.addParticle(ParticleTypes.EXPLOSION, this.x, this.y, this.z, 1.0, 0.0, 0.0)
            }
        }

        if (flag) {
            val list = ObjectArrayList<Pair<ItemStack, BlockPos>>()
            val flag1 = this.indirectSourceEntity is Player

            val blowList = this.toBlow.stream().filter { !this.level.getBlockState(it).isAir }.toList()

            for (blockpos in blowList) {
                val blockstate = this.level.getBlockState(blockpos)

                val blockpos1 = blockpos.immutable()
                this.level.profiler.push("explosion_blocks")

                if (this.level is ServerLevel) {
                    val blockEntity = if (blockstate.hasBlockEntity()) this.level.getBlockEntity(blockpos) else null
                    val lootParamsBuilder = (LootParams.Builder(level))
                        .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(blockpos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                        .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity)
                        .withOptionalParameter(LootContextParams.THIS_ENTITY, this.pSource)
                        .withParameter(LootContextParams.EXPLOSION_RADIUS, this.radius)

                    blockstate.spawnAfterBreak(level, blockpos, ItemStack.EMPTY, flag1)
                    blockstate.getDrops(lootParamsBuilder).forEach {
                        addBlockDrops(list, it, blockpos1)
                    }
                }

                blockstate.onBlockExploded(this.level, blockpos, this)
                this.level.profiler.pop()
            }

            for (pair in list) {
                Block.popResource(this.level, pair.getSecond(), pair.getFirst())
            }
        }
    }

    class Builder(private val directSource: Entity) {
        private val level: Level = directSource.level()
        private var sourceEntity: Entity?
        private var attackerEntity: Entity?
        private var damage = 0f
        private var radius = 0f
        private var particleType: ParticleTool.ParticleType = ParticleTool.ParticleType.MINI
        private var destroyBlock: Supplier<BlockInteraction> =
            Supplier { if (ExplosionConfig.EXPLOSION_DESTROY.get()) BlockInteraction.DESTROY else BlockInteraction.KEEP }
        private var fireTime = 0
        private var damageMultiplier = 1f
        private var damageSource: DamageSource? = null
        private var particlePosition: Vec3? = null
        private var emitFx = true
        private var explosionCauseId: ResourceLocation? = null
        private var explosionProfileId: ResourceLocation? = null
        var position: Vec3

        init {
            this.sourceEntity = directSource
            this.attackerEntity = directSource
            this.position = Vec3(directSource.x, directSource.eyeY, directSource.z)
        }

        fun source(source: Entity?): Builder {
            this.sourceEntity = source
            return this
        }

        fun attacker(attacker: Entity?): Builder {
            this.attackerEntity = attacker
            return this
        }

        fun damage(damage: Float): Builder {
            this.damage = damage
            return this
        }

        fun radius(radius: Float): Builder {
            this.radius = radius
            return this
        }

        fun withParticleType(particleType: ParticleTool.ParticleType): Builder {
            this.particleType = particleType
            return this
        }

        fun emitFx(emitFx: Boolean): Builder {
            this.emitFx = emitFx
            return this
        }

        fun explosionCause(causeId: ResourceLocation?): Builder {
            this.explosionCauseId = causeId
            return this
        }

        fun explosionProfile(profileId: ResourceLocation?): Builder {
            this.explosionProfileId = profileId
            return this
        }

        fun destroyBlock(destroyBlock: Supplier<BlockInteraction>): Builder {
            this.destroyBlock = destroyBlock
            return this
        }

        fun keepBlock(): Builder {
            this.destroyBlock = Supplier { BlockInteraction.KEEP }
            return this
        }

        fun fireTime(fireTime: Int): Builder {
            this.fireTime = fireTime
            return this
        }

        fun damageMultiplier(damageMultiplier: Float): Builder {
            this.damageMultiplier = damageMultiplier
            return this
        }

        fun damageSource(damageSource: DamageSource?): Builder {
            this.damageSource = damageSource
            return this
        }

        fun position(position: Vec3): Builder {
            this.position = position
            return this
        }

        fun particlePosition(particlePosition: Vec3?): Builder {
            this.particlePosition = particlePosition
            return this
        }

        fun explode() {
            if (level.isClientSide) return

            val source =
                (if (this.damageSource != null) this.damageSource else ModDamageTypes.causeCustomExplosionDamage(
                    level.registryAccess(),
                    sourceEntity,
                    attackerEntity
                ))!!

            val blockInteraction = if (ProjectileProfiles.suppressesVehicleBlockDamage(directSource)) {
                BlockInteraction.KEEP
            } else {
                destroyBlock.get()
            }
            val customExplosion = CustomExplosion(
                level, directSource,
                source, damage,
                position.x, position.y, position.z, radius, blockInteraction
            )
                .setFireTime(fireTime)
                .setDamageMultiplier(damageMultiplier)
            customExplosion.explode()
            ForgeEventFactory.onExplosionStart(directSource.level(), customExplosion)
            customExplosion.finalizeExplosion(false)

            ParticleTool.dispatchExplosionFx(
                directSource.level(),
                ExplosionFxContext(
                    directSource = directSource,
                    attacker = attackerEntity,
                    gameplayPosition = position,
                    particlePosition = particlePosition ?: position,
                    radius = radius,
                    emitFx = emitFx,
                    particleType = particleType,
                    causeId = explosionCauseId,
                    profileId = explosionProfileId
                )
            )
        }
    }

    companion object {
        @JvmStatic
        fun addBlockDrops(
            pDropPositionArray: ObjectArrayList<Pair<ItemStack, BlockPos>>,
            pStack: ItemStack,
            pPos: BlockPos
        ) {
            val i = pDropPositionArray.size

            for (j in 0..<i) {
                val pair = pDropPositionArray[j]
                val itemstack = pair.getFirst()
                if (ItemEntity.areMergable(itemstack, pStack)) {
                    val itemstack1 = ItemEntity.merge(itemstack, pStack, 16)
                    pDropPositionArray[j] = Pair.of(itemstack1, pair.getSecond())
                    if (pStack.isEmpty) {
                        return
                    }
                }
            }

            pDropPositionArray.add(Pair.of(pStack, pPos))
        }
    }
}
