package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent.HitBlock
import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent.HitEntity
import com.atsuishio.superbwarfare.api.projectile.ProfiledProjectile
import com.atsuishio.superbwarfare.api.projectile.BallisticSync
import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess
import com.atsuishio.superbwarfare.api.projectile.SmoothedBallisticProjectile
import com.atsuishio.superbwarfare.api.vehicle.weapon.AircraftRoundConsolidation
import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile
import com.atsuishio.superbwarfare.api.projectile.SequencedProjectile
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDamagePolicy
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResolver
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult
import com.atsuishio.superbwarfare.client.particle.BulletDecalOption
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.config.server.ProjectileConfig
import com.atsuishio.superbwarfare.entity.OBBEntity
import com.atsuishio.superbwarfare.entity.living.DPSGeneratorEntity
import com.atsuishio.superbwarfare.entity.living.TargetEntity
import com.atsuishio.superbwarfare.entity.mixin.ICustomKnockback
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.*
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeGunFireAbsoluteDamage
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeGunFireDamage
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeGunFireHeadshotAbsoluteDamage
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeGunFireHeadshotDamage
import com.atsuishio.superbwarfare.item.misc.TranscriptItem
import com.atsuishio.superbwarfare.item.weapon.BeastItem.Companion.beastKill
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.network.message.receive.ClientMotionSyncMessage
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.tools.*
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import com.atsuishio.superbwarfare.tools.blast.TntEquivalents
import com.atsuishio.superbwarfare.tools.FormatTool.format1D
import com.atsuishio.superbwarfare.tools.HitboxHelper.getBoundingBox
import com.atsuishio.superbwarfare.tools.HitboxHelper.getVelocity
import com.atsuishio.superbwarfare.tools.VectorTool.isInLiquid
import com.atsuishio.superbwarfare.world.phys.EntityResult
import com.atsuishio.superbwarfare.world.phys.ExtendedEntityRayTraceResult
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection
import com.atsuishio.superbwarfare.world.phys.ProjectileContact
import com.atsuishio.superbwarfare.world.phys.ProjectileSweepTraversal
import com.atsuishio.superbwarfare.diagnostics.ProjectileHitDiagnostics
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.core.BlockPos
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.entity.PartEntity
import net.minecraftforge.entity.IEntityAdditionalSpawnData
import net.minecraftforge.network.NetworkHooks
import java.util.*
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

@Suppress("unused")
open class ProjectileEntity(entityType: EntityType<out ProjectileEntity>, level: Level) : Projectile(entityType, level),
    CustomSyncMotionEntity, ExplosiveProjectile, IEntityAdditionalSpawnData, ProfiledProjectile, SequencedProjectile,
    ProjectileImpactDamagePolicy, FarProjectileAccess, SmoothedBallisticProjectile {
    override fun farProjectileExplosionRadius(): Double =
        maxOf(explosionRadius.toDouble(), TntBlast.farQueryRadius(this))
    // This entity uses findEntitiesOnPath's +1 query, not ProjectileUtilMixin's +8 query.
    // Include the entity-section lookup's two-block neighbor margin as well.
    override fun farProjectileCollisionPadding(): Double = 3.0
    override fun farProjectileLifetimeTicks(): Int = (if (fireLevel > 0) 10 else life).coerceIn(0, 2399) + 1
    override fun farProjectileTerminatesNextTick(currentAge: Int): Boolean = currentAge >= (if (fireLevel > 0) 10 else life)
    private var _projectileProfileId: net.minecraft.resources.ResourceLocation? = null
    private var _resolvedProjectileProfile: ResolvedProjectileProfile? = null
    private var _projectileShotSequence = 0L
    private var _projectileDimensions: EntityDimensions? = null
    private var activeImpactResult: ProjectileImpactResult? = null
    private var lastImpactPassed = false
    private var lastImpactStopsTraversal = false
    /** A TNT-equivalent charge detonates once, even for a round that penetrates several targets. */
    private var tntDetonated = false
    /** Legacy persisted provenance used to retire obsolete server-side impact fragments. */
    private var impactShrapnel = false
    /** Server: state at the start of the current step, compared with the clients' prediction. */
    private var ballisticStartPosition: Vec3? = null
    private var ballisticStartVelocity: Vec3 = Vec3.ZERO
    /** Client: outstanding step-aligned correction, blended over a few ticks. */
    private val ballisticCorrection = BallisticSync.Correction()
    /** Client: spawn data (exact velocity) has been applied; later tracker velocity is clamped. */
    private var ballisticSpawned = false

    // 子弹的发射者，可以为空
    var shooter: Entity? = null
        get() {
            if (field == null) {
                field = owner
                shooterId = field?.id ?: 0
            }
            return field
        }
        protected set(value) {
            field = value
            shooterId = value?.id ?: 0
        }

    // 子弹的发射者的ID
    var shooterId: Int = 0
        protected set

    // 子弹的伤害
    private var damage = 1f

    // 子弹的爆头倍率
    private var headShot = 1f

    // 子弹的打腿倍率
    private var legShot = 0.5f

    // 是否为野兽弹
    private var beast = false

    // 子弹是否是瞄准时发射的
    var isZoom: Boolean = false
        private set

    // 子弹的穿甲比例
    var bypassArmorRate: Float = 0.0f
        private set

    // 爆炸伤害（用于高爆弹等）
    private var explosionDamage = 0.0f

    // 爆炸半径（用于高爆弹等）
    private var explosionRadius = 0.0f

    // 燃烧弹等级
    private var fireLevel = 0

    // 是否为龙息弹
    private var dragonBreath = false

    // 击退力度
    private var knockback = 0.05f

    // 出膛速度
    private var velocity = 20f

    // 是否强制击退生物
    private var forceKnockback = false

    // 是否能穿墙
    var isPenetrating: Boolean = false

    // 子弹造成的状态效果
    private val mobEffects = arrayListOf<MobEffectInstance>()

    // 发射子弹的武器ID
    var gunItemId: String? = null
        private set

    // 重力
    private var gravity = 0.05f
    private var life = 40

    init {
        this.noCulling = true
    }

    constructor(level: Level) : this(ModEntities.PROJECTILE.get(), level)

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        ProjectileProfiles.writeAdditionalSaveData(this, compound)

        val projectileData = CompoundTag()
        projectileData.putInt("Version", 1)
        projectileData.putFloat("Damage", damage)
        projectileData.putFloat("HeadShot", headShot)
        projectileData.putFloat("LegShot", legShot)
        projectileData.putBoolean("Beast", beast)
        projectileData.putBoolean("Zoom", isZoom)
        projectileData.putFloat("BypassArmorRate", bypassArmorRate)
        projectileData.putFloat("ExplosionDamage", explosionDamage)
        projectileData.putFloat("ExplosionRadius", explosionRadius)
        projectileData.putInt("FireLevel", fireLevel)
        projectileData.putBoolean("DragonBreath", dragonBreath)
        projectileData.putFloat("Knockback", knockback)
        projectileData.putFloat("Velocity", velocity)
        projectileData.putBoolean("ForceKnockback", forceKnockback)
        projectileData.putBoolean("Penetrating", isPenetrating)
        projectileData.putBoolean("ImpactShrapnel", isImpactShrapnel())
        projectileData.putFloat("Gravity", gravity)
        projectileData.putInt("Life", life)
        projectileData.putInt("Age", tickCount.coerceAtLeast(0))
        gunItemId?.let { projectileData.putString("GunItemId", it) }

        val effects = ListTag()
        mobEffects.forEach { effects.add(it.save(CompoundTag())) }
        projectileData.put("MobEffects", effects)
        compound.put(PROJECTILE_DATA_TAG, projectileData)
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        ProjectileProfiles.readAdditionalSaveData(this, compound)

        if (!compound.contains(PROJECTILE_DATA_TAG, Tag.TAG_COMPOUND.toInt())) return
        val projectileData = compound.getCompound(PROJECTILE_DATA_TAG)
        projectileData.readIfPresent("Damage", Tag.TAG_FLOAT.toInt()) { damage = getFloat(it) }
        projectileData.readIfPresent("HeadShot", Tag.TAG_FLOAT.toInt()) { headShot = getFloat(it) }
        projectileData.readIfPresent("LegShot", Tag.TAG_FLOAT.toInt()) { legShot = getFloat(it) }
        projectileData.readIfPresent("Beast", Tag.TAG_BYTE.toInt()) { beast = getBoolean(it) }
        projectileData.readIfPresent("Zoom", Tag.TAG_BYTE.toInt()) { isZoom = getBoolean(it) }
        projectileData.readIfPresent("BypassArmorRate", Tag.TAG_FLOAT.toInt()) { bypassArmorRate = getFloat(it) }
        projectileData.readIfPresent("ExplosionDamage", Tag.TAG_FLOAT.toInt()) { explosionDamage = getFloat(it) }
        projectileData.readIfPresent("ExplosionRadius", Tag.TAG_FLOAT.toInt()) { explosionRadius = getFloat(it) }
        projectileData.readIfPresent("FireLevel", Tag.TAG_INT.toInt()) { fireLevel = getInt(it) }
        projectileData.readIfPresent("DragonBreath", Tag.TAG_BYTE.toInt()) { dragonBreath = getBoolean(it) }
        projectileData.readIfPresent("Knockback", Tag.TAG_FLOAT.toInt()) { knockback = getFloat(it) }
        projectileData.readIfPresent("Velocity", Tag.TAG_FLOAT.toInt()) { velocity = getFloat(it) }
        projectileData.readIfPresent("ForceKnockback", Tag.TAG_BYTE.toInt()) { forceKnockback = getBoolean(it) }
        projectileData.readIfPresent("Penetrating", Tag.TAG_BYTE.toInt()) { isPenetrating = getBoolean(it) }
        projectileData.readIfPresent("ImpactShrapnel", Tag.TAG_BYTE.toInt()) {
            impactShrapnel = getBoolean(it)
            entityData.set(IMPACT_SHRAPNEL, impactShrapnel)
        }
        if (retireImpactShrapnel()) return
        projectileData.readIfPresent("Gravity", Tag.TAG_FLOAT.toInt()) { gravity = getFloat(it) }
        projectileData.readIfPresent("Life", Tag.TAG_INT.toInt()) { life = getInt(it) }
        if (impactShrapnel) entityData.set(IMPACT_SHRAPNEL_LIFE, life.coerceIn(1, 16))
        projectileData.readIfPresent("Age", Tag.TAG_INT.toInt()) { tickCount = getInt(it).coerceAtLeast(0) }
        projectileData.readIfPresent("GunItemId", Tag.TAG_STRING.toInt()) { gunItemId = getString(it) }
        if (projectileData.contains("MobEffects", Tag.TAG_LIST.toInt())) {
            mobEffects.clear()
            val effects = projectileData.getList("MobEffects", Tag.TAG_COMPOUND.toInt())
            for (index in 0 until effects.size) {
                MobEffectInstance.load(effects.getCompound(index))?.let(mobEffects::add)
            }
        }
    }

    override fun getProjectileProfileId() = _projectileProfileId

    override fun getResolvedProjectileProfile() = _resolvedProjectileProfile

    override fun getProjectileShotSequence() = _projectileShotSequence

    override fun setProjectileShotSequence(sequence: Long) {
        _projectileShotSequence = sequence.coerceAtLeast(0L)
    }

    override fun setProjectileProfileId(id: net.minecraft.resources.ResourceLocation?) {
        applyProjectileProfile(id, ProjectileProfiles.resolve(id))
    }

    override fun copyProjectileProfileFrom(source: ProfiledProjectile) {
        applyProjectileProfile(source.getProjectileProfileId(), source.getResolvedProjectileProfile())
        setProjectileShotSequence((source as? SequencedProjectile)?.getProjectileShotSequence() ?: 0L)
    }

    private fun applyProjectileProfile(
        id: net.minecraft.resources.ResourceLocation?,
        profile: ResolvedProjectileProfile?,
    ) {
        _projectileProfileId = id
        _resolvedProjectileProfile = profile
        _projectileDimensions = profile?.collision?.let { EntityDimensions.fixed(it.width, it.height) }
        refreshDimensions()
    }

    override fun getDimensions(pPose: Pose): EntityDimensions {
        return _projectileDimensions ?: super.getDimensions(pPose)
    }

    override fun writeSpawnData(buffer: FriendlyByteBuf) {
        val motion = this.deltaMovement
        buffer.writeFloat(motion.x.toFloat())
        buffer.writeFloat(motion.y.toFloat())
        buffer.writeFloat(motion.z.toFloat())
        buffer.writeVarInt(this.tickCount)
        // The client flies the same deterministic step, so it needs the authored drop per tick.
        buffer.writeFloat(this.gravity)
        buffer.writeVarInt(AircraftRoundConsolidation.weight(this))
        ProjectileProfiles.writeSpawnData(this, buffer)
    }

    override fun readSpawnData(additionalData: FriendlyByteBuf) {
        this.setDeltaMovement(
            additionalData.readFloat().toDouble(),
            additionalData.readFloat().toDouble(),
            additionalData.readFloat().toDouble(),
        )
        this.tickCount = additionalData.readVarInt()
        val syncedGravity = additionalData.readFloat()
        if (syncedGravity.isFinite()) this.gravity = syncedGravity
        AircraftRoundConsolidation.mark(this, additionalData.readVarInt())
        ballisticSpawned = true
        ProjectileProfiles.readSpawnData(this, additionalData)
        this.setOldPosAndRot()
    }

    override fun smoothsBallisticFlight(): Boolean =
        motionSyncMode() == FastThrowableProjectile.MotionSyncMode.ENTITY_INTERVAL

    override fun ballisticStep(velocity: Vec3): Vec3 =
        NominalProjectileMotion.afterStep(velocity, this.gravity.toDouble())

    override fun acceptBallisticState(tick: Int, position: Vec3, velocity: Vec3) {
        if (!level().isClientSide || isRemoved) return
        val gravity = this.gravity.toDouble()
        val aligned = BallisticSync.align(tick, tickCount, position, velocity, this::ballisticStep) {
            it.add(0.0, gravity, 0.0)
        }
        if (aligned == null) {
            snapBallistic(position, velocity)
            return
        }
        this.deltaMovement = aligned.second
        if (!ballisticCorrection.offer(aligned.first.subtract(position()), aligned.second.length())) {
            snapBallistic(aligned.first, aligned.second)
        }
    }

    private fun snapBallistic(position: Vec3, velocity: Vec3) {
        ballisticCorrection.clear()
        this.setPos(position)
        this.xo = position.x; this.yo = position.y; this.zo = position.z
        this.xOld = position.x; this.yOld = position.y; this.zOld = position.z
        this.deltaMovement = velocity
    }

    /**
     * Vanilla motion packets clamp each axis to 3.9 blocks/tick. After the exact spawn velocity, a
     * deterministic round takes velocity only from step-aligned motion messages.
     */
    override fun lerpMotion(x: Double, y: Double, z: Double) {
        if (ballisticSpawned && level().isClientSide && smoothsBallisticFlight()) return
        super.lerpMotion(x, y, z)
    }

    /** Tracker positions carry no simulation step; step-aligned motion messages own corrections. */
    override fun lerpTo(x: Double, y: Double, z: Double, yRot: Float, xRot: Float, steps: Int, teleport: Boolean) {
        if (level().isClientSide && smoothsBallisticFlight()) {
            val target = Vec3(x, y, z)
            if (BallisticSync.grossDivergence(target.subtract(position()), deltaMovement)) {
                snapBallistic(target, deltaMovement)
            }
            return
        }
        super.lerpTo(x, y, z, yRot, xRot, steps, teleport)
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return NetworkHooks.getEntitySpawningPacket(this)
    }

    protected fun findEntityOnPath(startVec: Vec3, endVec: Vec3): EntityResult? {
        var nearest: EntityResult? = null
        val entities = this.level()
            .getEntities(
                this,
                this.boundingBox
                    .expandTowards(this.deltaMovement)
                    .inflate((if (this.beast) 3 else 1).toDouble()),
                PROJECTILE_TARGETS
            )
        var closestDistance = Double.MAX_VALUE

        for (entity in entities) {
            if (entity == this.shooter || this.shooter != null && entity == this.shooter!!.vehicle) continue
            if (this.shooter != null && entity.getRootVehicle() === this.shooter!!.getRootVehicle()) continue

            if (entity is TargetEntity && entity.getEntityData().get(TargetEntity.DOWN_TIME) > 0) continue
            if (entity is DPSGeneratorEntity && entity.getEntityData().get(DPSGeneratorEntity.DOWN_TIME) > 0) continue

            val result = this.getHitResult(entity, startVec, endVec) ?: continue

            val hitPos = result.hitVec

            val distanceToHit = startVec.distanceTo(hitPos)
            if (distanceToHit < closestDistance) {
                nearest = result
                closestDistance = distanceToHit
            }
        }
        return nearest
    }

    protected fun findEntitiesOnPath(startVec: Vec3, endVec: Vec3): MutableList<EntityResult> {
        val hitEntities: MutableList<EntityResult> = arrayListOf()
        val entities = this.level().getEntities(
            this,
            this.boundingBox
                .expandTowards(this.deltaMovement)
                .inflate(1.0),
            PROJECTILE_TARGETS
        )
        for (entity in entities) {
            if (this.shooter == null || entity !== shooter && entity !== this.shooter!!.vehicle) {
                val result = this.getHitResult(entity, startVec, endVec) ?: continue
                if (entity.vehicle != null && this.shooter != null && entity.vehicle === this.shooter!!.vehicle) continue
                hitEntities.add(result)
            }
        }
        return hitEntities
    }

    /**
     * From TaC-Z
     */
    private fun getHitResult(entity: Entity, startVec: Vec3, endVec: Vec3): EntityResult? {
        val expandHeight = if (entity is Player && !entity.isCrouching) 0.0625 else 0.0

        var hitPos: Vec3? = null
        var hitPart: OBB.Part? = null
        // Guided ATGMs have a dedicated, reduced interception volume.  Do not let the
        // projectile's normal moving-entity/AABB compensation turn a near miss into a strike;
        // other target types retain the existing hitbox semantics unchanged.
        if (!level().isClientSide && entity is ProjectileCollisionTarget && entity.usesDetailedProjectileCollision()) {
            val hit = ProjectileHitDiagnostics.query(this, entity, "bullet_detailed", startVec, endVec,
                entity.clipProjectile(startVec, endVec)) ?: return null
            hitPos = hit.point()
            hitPart = hit.part()
        } else if (entity is WireGuideMissileEntity && this !is WireGuideMissileEntity) {
            hitPos = WireGuideMissileEntity.preciseInterceptionHitPoint(entity, startVec, endVec)
        } else if (entity is OBBEntity && !entity.enableAABB()) {
            val hit = ProjectileHitDiagnostics.query(this, entity, "bullet_obb", startVec, endVec,
                ProjectileHitSelection.nearestObb(entity.getOBBs(), startVec, endVec, 0.0)) ?: return null
            hitPos = hit.point()
            hitPart = hit.part()
        } else {
            var boundingBox = entity.boundingBox
            var velocity = Vec3(entity.x - entity.xOld, entity.y - entity.yOld, entity.z - entity.zOld)

            val shooter = this.shooter
            if (entity is ServerPlayer && shooter is ServerPlayer) {
                val ping = Mth.floor((shooter.latency / 1000.0) * 20.0 + 0.5)
                boundingBox = getBoundingBox(entity, ping)
                velocity = getVelocity(entity, ping)
            }
            boundingBox = boundingBox.expandTowards(0.0, expandHeight, 0.0)
            boundingBox = boundingBox.expandTowards(velocity.x, velocity.y, velocity.z)

            val playerHitboxOffset = 3.0
            if (entity is ServerPlayer) {
                if (entity.vehicle != null) {
                    boundingBox = boundingBox.move(
                        velocity.multiply(
                            playerHitboxOffset / 2,
                            playerHitboxOffset / 2,
                            playerHitboxOffset / 2
                        )
                    )
                }
                boundingBox =
                    boundingBox.move(velocity.multiply(playerHitboxOffset, playerHitboxOffset, playerHitboxOffset))
            }

            if (entity.vehicle != null) {
                boundingBox = boundingBox.move(velocity.multiply(-2.5, -2.5, -2.5))
            }
            boundingBox = boundingBox.move(velocity.multiply(-5.0, -5.0, -5.0))

            if (this.beast) {
                boundingBox = boundingBox.inflate(3.0)
            }

            hitPos = boundingBox.clip(startVec, endVec).orElse(null)
        }

        if (hitPos == null) {
            return null
        }
        val hitBoxPos = hitPos.subtract(entity.position())
        var headshot = false
        var legShot = false
        val eyeHeight = entity.eyeHeight
        val bodyHeight = entity.bbHeight
        if ((eyeHeight - 0.25) < hitBoxPos.y && hitBoxPos.y < (eyeHeight + 0.3) && entity is LivingEntity) {
            headshot = true
        }
        if (hitBoxPos.y < (0.33 * bodyHeight) && entity is LivingEntity) {
            legShot = true
        }

        return EntityResult(entity, hitPos, headshot, legShot, hitPart)
    }

    override fun defineSynchedData() {
        this.entityData.define(COLOR_R, DEFAULT_R)
        this.entityData.define(COLOR_G, DEFAULT_G)
        this.entityData.define(COLOR_B, DEFAULT_B)
        this.entityData.define(IMPACT_SHRAPNEL, false)
        this.entityData.define(IMPACT_SHRAPNEL_LIFE, 0)
    }

    override fun tick() {
        if (retireImpactShrapnel()) return
        super.tick()
        this.updateHeading()

        val vec = this.deltaMovement

        val level = this.level()
        if (!level.isClientSide()) {
            ballisticStartPosition = this.position()
            ballisticStartVelocity = vec
            val startVec = this.position()
            var endVec = startVec.add(this.deltaMovement)
            var result: HitResult? = if (this.isPenetrating || this.beast) {
                rayTraceBlocks(level,
                    ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this),
                    Predicate { true })
            } else {
                rayTraceNominalBlocks(level,
                    ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))
            }

            val fluidResult: BlockHitResult =
                rayTraceBlocks(
                    level,
                    ClipContext(startVec, endVec, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this),
                    if (this.isPenetrating || this.beast) Predicate { true } else if (ProjectileConfig.PROJECTILE_DESTROY_BLOCKS.get()) IGNORE_LIST.and(
                        Predicate { input -> !input.`is`(ModTags.Blocks.BULLET_CAN_DESTROY) }) else IGNORE_LIST
                )

            if (result != null && result.type != HitResult.Type.MISS) {
                endVec = result.getLocation()
            }

            val entityResults = findEntitiesOnPath(startVec, endVec)
            ProjectileHitSelection.sortFromStart(entityResults, startVec) { it.hitVec }

            val blockResult = result
            if (EliteDiagnostics.isEnabled(level)) {
                EliteDiagnostics.record(this, "hitreg", "bullet_sweep", "start", startVec,
                    "clipped_end", endVec, "block", blockResult?.type, "contacts", entityResults.size)
            }
            ProjectileSweepTraversal.visit(entityResults, { entityResult ->
                var entityHit: ExtendedEntityRayTraceResult? = ExtendedEntityRayTraceResult(entityResult)
                val resEntity = entityResult.entity
                val shooter = this.shooter
                if (resEntity is Player) {
                    if (shooter is Player && !shooter.canHarmPlayer(resEntity)) {
                        entityHit = null
                    }
                }
                var stopsTraversal = false
                val passedImpact = if (entityHit != null) {
                    this.onHit(entityHit)
                    stopsTraversal = takeLastImpactStopsTraversal()
                    takeLastImpactPassed()
                } else false

                if (stopsTraversal || isRemoved) {
                    return@visit false
                }

                if (!this.beast && !passedImpact) {
                    this.bypassArmorRate -= 0.2f
                    if (this.bypassArmorRate < 0.8f) {
                        if (entityHit != null && !(resEntity is TargetEntity && resEntity.getEntityData()
                                .get(TargetEntity.DOWN_TIME) > 0)
                            && !(resEntity is DPSGeneratorEntity && resEntity.getEntityData()
                                .get(DPSGeneratorEntity.DOWN_TIME) > 0)
                        ) {
                            return@visit false
                        }
                    }
                }
                true
            }, {
                // A passed entity must not erase the terrain endpoint from this same sweep.
                if (!isRemoved && blockResult != null) this.onHit(blockResult)
            })

            this.onHitWater(fluidResult.getLocation(), fluidResult)
            this.setPos(this.x + vec.x, this.y + vec.y, this.z + vec.z)
        } else {
            this.setPosRaw(this.x + vec.x, this.y + vec.y, this.z + vec.z)
            ballisticCorrection.next()?.let { this.setPosRaw(this.x + it.x, this.y + it.y, this.z + it.z) }
        }

        this.deltaMovement = NominalProjectileMotion.afterStep(this.deltaMovement, this.gravity.toDouble())

        if (this.tickCount > (if (fireLevel > 0) 10 else life)) {
            this.discard()
        }

        if (fireLevel > 0 && dragonBreath && level is ServerLevel) {
            val randomPos = this.tickCount * 0.08 * (Math.random() - 0.5)
            ParticleTool.sendParticle(
                level,
                ParticleTypes.FLAME,
                (this.xo + this.x) / 2 + randomPos,
                (this.yo + this.y) / 2 + randomPos,
                (this.zo + this.z) / 2 + randomPos,
                0,
                this.deltaMovement.x,
                this.deltaMovement.y,
                this.deltaMovement.z,
                max(this.deltaMovement.length() - 1.1 * this.tickCount, 0.2),
                true
            )
        }

        if (level is ServerLevel) {
            if (isInLiquid(level, position())) {
                this.deltaMovement = this.deltaMovement.multiply(0.75, 0.75, 0.75)
            }
            if (this.isInWater) {
                val l = deltaMovement.length()
                var i = 0.0
                while (i < l) {
                    val startPos = Vec3(this.xo, this.yo, this.zo)
                    val pos = startPos.add(deltaMovement.normalize().scale(i))
                    ParticleTool.sendParticle(
                        level, ParticleTypes.BUBBLE_COLUMN_UP, pos.x, pos.y, pos.z,
                        1, 0.0, 0.0, 0.0, 0.001, true
                    )
                    i++
                }
            }
        }

        this.syncMotion()
    }

    override fun syncMotion() {
        if (this.level().isClientSide) return
        when (motionSyncMode()) {
            FastThrowableProjectile.MotionSyncMode.NONE -> return
            FastThrowableProjectile.MotionSyncMode.ENTITY_INTERVAL -> {
                // Clients run the same step; publish only a departure from it or a sparse realignment.
                val start = ballisticStartPosition
                ballisticStartPosition = null
                if (isRemoved) return
                val deviated = start == null || BallisticSync.deviates(start, ballisticStartVelocity,
                    position(), deltaMovement, ballisticStep(ballisticStartVelocity))
                if (!deviated && this.tickCount % BallisticSync.CORRECTION_INTERVAL_TICKS != 0) return
                NetworkTelemetry.recordSystemWork("projectile.motion_correction")
                sendPacketToTrackingThis(ClientMotionSyncMessage.ballistic(this))
                return
            }
            FastThrowableProjectile.MotionSyncMode.EVERY_TICK -> Unit
        }

        NetworkTelemetry.recordSystemWork("projectile.motion_correction")
        sendPacketToTrackingThis(ClientMotionSyncMessage(this))
    }

    /** Unguided bullets fly deterministically on the client; a profile may still opt into EVERY_TICK. */
    open fun motionSyncMode(): FastThrowableProjectile.MotionSyncMode {
        return ProjectileProfiles.motionSyncMode(this, FastThrowableProjectile.MotionSyncMode.ENTITY_INTERVAL)
    }

    override fun onHit(result: HitResult) {
        lastImpactPassed = false
        lastImpactStopsTraversal = false
        if (retireImpactShrapnel()) return
        if (result.type == HitResult.Type.MISS) {
            return
        }
        if (result is ExtendedEntityRayTraceResult) {
            val entity = result.entity
            if (entity.id == this.shooterId
                || this.shooter is Player && entity.hasIndirectPassenger(shooter!!)
            ) {
                return
            }
            // Publish only the selected hit's metadata/effect, never a later broadphase candidate's.
            OBBHitter.getInstance(this).`sbw$setProjectileContact`(ProjectileContact(
                entity.uuid, level().gameTime, result.hitPart ?: OBB.Part.EMPTY))
            if (result.hitPart != null && level() is ServerLevel) {
                level().playSound(null, BlockPos.containing(result.location), ModSounds.HIT.get(), SoundSource.PLAYERS, 1f, 1f)
            }
        } else {
            OBBHitter.getInstance(this).`sbw$setProjectileContact`(null)
        }
        val legacyEventCancelled = when (result) {
            is ExtendedEntityRayTraceResult -> postEvent(HitEntity(this.shooter, this, result))
            is BlockHitResult -> postEvent(
                HitBlock(
                    result.blockPos,
                    level().getBlockState(result.blockPos),
                    result.direction,
                    this.shooter,
                    this,
                    result.location
                )
            )
            else -> false
        }
        if (legacyEventCancelled) {
            // Cancellation suppresses this impact. A listener that explicitly
            // discarded the projectile still terminates same-tick traversal.
            lastImpactPassed = true
            lastImpactStopsTraversal = isRemoved
            return
        }
        val context = when (result) {
            is ExtendedEntityRayTraceResult -> ProjectileImpactContext.entity(
                this.shooter,
                this,
                result.entity,
                result.location,
                causeGunFireDamage(this.level().registryAccess(), this, this.shooter),
            )
            is BlockHitResult -> ProjectileImpactContext.block(
                this.shooter,
                this,
                result.location,
                result.blockPos,
                this.level().getBlockState(result.blockPos),
                result.direction
            )
            else -> null
        }
        val resolution = context?.let(ProjectileImpactResolver::resolve)
            ?: ProjectileImpactResult.defaultResult()

        if (!resolution.continuesDefaultPipeline()) {
            lastImpactPassed = resolution.disposition == ProjectileImpactDisposition.PASS
            lastImpactStopsTraversal = ProjectileSweepTraversal.stops(resolution)
            if (resolution.consumesProjectile()) {
                // Armor owns the direct hit; a TNT-equivalent charge still detonates at the impact point.
                if (!tntDetonated && TntBlast.active(this)) {
                    activeImpactResult = resolution
                    try {
                        explosionBullet(this, result.location)
                    } finally {
                        activeImpactResult = null
                    }
                }
                this.discard()
            }
            return
        }

        resolution.residualDamage?.let { this.damage = it }
        resolution.residualExplosionDamage?.let { this.explosionDamage = it }
        resolution.residualExplosionRadius?.let { this.explosionRadius = it }
        activeImpactResult = resolution
        try {
        if (result is BlockHitResult) {
            val level = this.level()
            if (result.type == HitResult.Type.MISS) {
                return
            }
            val resultPos = result.blockPos
            val state = level.getBlockState(resultPos)
            val event = state.block.getSoundType(state, level, resultPos, this).breakSound
            if (!resolution.suppressesDefaultVisuals()) {
                level.playSound(
                    null,
                    result.getLocation().x,
                    result.getLocation().y,
                    result.getLocation().z,
                    event,
                    SoundSource.AMBIENT,
                    1f,
                    1f
                )
            }
            val hitVec = result.getLocation()

            this.onHitBlock(hitVec, result)
            if (shouldExplode(resolution)) {
                explosionBullet(this, hitVec)
            }
            if (fireLevel > 0 && level is ServerLevel && !resolution.suppressesDefaultVisuals()) {
                ParticleTool.sendParticle(
                    level, ParticleTypes.LAVA, hitVec.x, hitVec.y, hitVec.z,
                    3, 0.0, 0.0, 0.0, 0.5, true
                )
            }
        }

        if (result is ExtendedEntityRayTraceResult) {
            val entity = result.entity
            emitDefaultEntityImpactParticles(entity, result.location)
            if (shouldExplode(resolution)) {
                explosionBullet(this, result.location)
            }
            this.onHitEntity(entity, result)
            entity.invulnerableTime = 0
        }
        } finally {
            activeImpactResult = null
        }
    }

    private fun takeLastImpactPassed(): Boolean {
        val passed = lastImpactPassed
        lastImpactPassed = false
        return passed
    }

    private fun takeLastImpactStopsTraversal(): Boolean {
        val stopsTraversal = lastImpactStopsTraversal
        lastImpactStopsTraversal = false
        return stopsTraversal
    }

    private fun emitDefaultEntityImpactParticles(entity: Entity, location: Vec3) {
        val level = this.level()
        if (activeImpactResult?.suppressesDefaultVisuals() == true
            || level !is ServerLevel
            || entity !is OBBEntity
            || entity.enableAABB()
        ) {
            return
        }
        ParticleTool.sendParticle(
            level, ModParticleTypes.FIRE_STAR.get(), location.x, location.y, location.z,
            2, 0.0, 0.0, 0.0, 0.2, false
        )
        ParticleTool.sendParticle(
            level, ParticleTypes.SMOKE, location.x, location.y, location.z,
            2, 0.0, 0.0, 0.0, 0.01, false
        )
    }

    private fun getRings(direction: Direction, hitVec: Vec3): Int {
        val x = abs(Mth.frac(hitVec.x) - 0.5)
        val y = abs(Mth.frac(hitVec.y) - 0.5)
        val z = abs(Mth.frac(hitVec.z) - 0.5)
        val axis = direction.axis
        val v: Double = if (axis === Direction.Axis.Y) {
            max(x, z)
        } else if (axis === Direction.Axis.Z) {
            max(x, y)
        } else {
            max(y, z)
        }

        return max(1, ceil(10.0 * ((0.5 - v) / 0.5).coerceIn(0.0, 1.0)).toInt())
    }

    open fun recordHitScore(direction: Direction, hitVec: Vec3) {
        val shooter = this.shooter ?: return
        val score = this.getRings(direction, hitVec)
        val distance = shooter.position().distanceTo(hitVec)

        if (shooter !is Player) {
            return
        }

        shooter.displayClientMessage(
            Component.literal(score.toString())
                .append(Component.translatable("tips.superbwarfare.shoot.rings"))
                .append(Component.literal(" " + format1D(distance, "m"))), false
        )

        if (shooter is ServerPlayer) {
            val holder = if (score == 10) Holder.direct(ModSounds.HEADSHOT.get())
            else Holder.direct(ModSounds.INDICATION.get())

            sendPacketTo(
                shooter,
                ClientboundSoundPacket(
                    holder,
                    SoundSource.PLAYERS,
                    shooter.x,
                    shooter.y,
                    shooter.z,
                    1f,
                    1f,
                    shooter.level().random.nextLong()
                )
            )
            sendPacketTo(shooter, ClientIndicatorMessage(if (score == 10) 1 else 0, 5))
        }

        val stack = shooter.offhandItem
        if (stack.`is`(ModItems.TRANSCRIPT.get())) {
            val size = 10

            val tags = stack.getOrCreateTag().getList(TranscriptItem.TAG_SCORES, Tag.TAG_COMPOUND.toInt())

            val queue: Queue<CompoundTag> = ArrayDeque<CompoundTag>()
            for (i in tags.indices) {
                queue.add(tags.getCompound(i))
            }

            val tag = CompoundTag()
            tag.putInt("Score", score)
            tag.putDouble("Distance", distance)
            queue.offer(tag)

            while (queue.size > size) {
                queue.poll()
            }

            val newTags = ListTag()
            newTags.addAll(queue)

            stack.getOrCreateTag().put(TranscriptItem.TAG_SCORES, newTags)
        }
    }

    protected fun onHitWater(location: Vec3, result: BlockHitResult) {
        val level = this.level()
        if (level is ServerLevel) {
            val pos = result.blockPos
            val face = result.direction
            val state = level().getBlockState(pos)

            val vx = face.stepX.toDouble()
            val vy = face.stepY.toDouble()
            val vz = face.stepZ.toDouble()
            val dir = Vec3(vx, vy, vz).add(deltaMovement.normalize().scale(-0.1))

            if (state.block === Blocks.WATER) {
                if (!this.isInWater) {
                    val particleData = CustomCloudOption(1f, 1f, 1f, 80, 0.5f, 1f, cooldown = false, light = false)
                    for (i in 0..9) {
                        val vec3 = randomVec(dir, 40.0)
                        ParticleTool.sendParticle(
                            level,
                            particleData,
                            location.x + 0.12 * i * dir.x,
                            location.y + 0.12 * i * dir.y,
                            location.z + 0.12 * i * dir.z,
                            0,
                            vec3.x,
                            vec3.y,
                            vec3.z,
                            15.0,
                            true
                        )
                    }

                    ParticleTool.spawnBulletHitWaterParticles(level, location)
                    level.playSound(
                        null,
                        BlockPos(location.x.toInt(), location.y.toInt(), location.z.toInt()),
                        ModSounds.HIT_WATER.get(),
                        SoundSource.BLOCKS,
                        1f,
                        1f
                    )

                    // 水下路径气泡
                    val l = deltaMovement.length()
                    var i = 0.0
                    while (i < l) {
                        val p = location.add(deltaMovement.normalize().scale(i))
                        ParticleTool.sendParticle(
                            level, ParticleTypes.BUBBLE_COLUMN_UP, p.x, p.y, p.z,
                            1, 0.0, 0.0, 0.0, 0.001, false
                        )
                        i++
                    }

                    this.deltaMovement = this.deltaMovement.multiply(0.1, 0.1, 0.1)
                }
            } else if (state.block === Blocks.LAVA) {
                if (!this.isInLava) {
                    val particleData = BlockParticleOption(ParticleTypes.BLOCK, state)
                    for (i in 0..6) {
                        val vec3 = randomVec(dir, 20.0)
                        ParticleTool.sendParticle(
                            level,
                            particleData,
                            location.x + 0.1 * i * dir.x,
                            location.y + 0.1 * i * dir.y,
                            location.z + 0.1 * i * dir.z,
                            0,
                            vec3.x,
                            vec3.y,
                            vec3.z,
                            10.0,
                            true
                        )
                    }
                    ParticleTool.sendParticle(
                        level, ParticleTypes.LAVA, location.x, location.y, location.z,
                        4, 0.0, 0.0, 0.0, 0.6, true
                    )
                    level.playSound(
                        null,
                        BlockPos(location.x.toInt(), location.y.toInt(), location.z.toInt()),
                        SoundEvents.LAVA_POP,
                        SoundSource.BLOCKS,
                        1f,
                        1f
                    )
                    this.discard()
                }
            }
        }
    }

    protected fun onHitBlock(location: Vec3, result: BlockHitResult) {
        val level = this.level()
        if (level is ServerLevel) {
            val pos = result.blockPos
            val face = result.direction
            val state = level().getBlockState(pos)

            val vx = face.stepX.toDouble()
            val vy = face.stepY.toDouble()
            val vz = face.stepZ.toDouble()
            val dir = Vec3(vx, vy, vz)

            if (this.beast) {
                if (activeImpactResult?.suppressesDefaultVisuals() != true) {
                    ParticleTool.sendParticle(
                        level,
                        ParticleTypes.END_ROD,
                        location.x,
                        location.y,
                        location.z,
                        15,
                        0.1,
                        0.1,
                        0.1,
                        0.05,
                        true
                    )
                }
            } else {
                if (activeImpactResult?.suppressesDefaultVisuals() != true) {
                    val bulletDecalOption = if (
                        this.entityData.get(COLOR_R) == DEFAULT_R
                        && this.entityData.get(COLOR_G) == DEFAULT_G
                        && this.entityData.get(COLOR_B) == DEFAULT_B
                    ) {
                        BulletDecalOption(result.direction, result.blockPos)
                    } else {
                        BulletDecalOption(
                            result.direction,
                            result.blockPos,
                            this.entityData.get(COLOR_R),
                            this.entityData.get(COLOR_G),
                            this.entityData.get(COLOR_B)
                        )
                    }
                    ParticleTool.sendParticle(
                        level,
                        bulletDecalOption,
                        location.x,
                        location.y,
                        location.z,
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        true
                    )
                    summonVectorParticle(level, state, location, dir)
                }

                this.discard()
            }
            if (activeImpactResult?.suppressesDefaultVisuals() != true) {
                level.playSound(
                    null,
                    BlockPos(location.x.toInt(), location.y.toInt(), location.z.toInt()),
                    ModSounds.LAND.get(),
                    SoundSource.BLOCKS,
                    1f,
                    1f
                )
            }
        }
    }

    open fun summonVectorParticle(serverLevel: ServerLevel, state: BlockState, pos: Vec3, dir: Vec3) {
        val particleData = BlockParticleOption(ParticleTypes.BLOCK, state)
        for (i in 0..6) {
            val vec3 = randomVec(dir, 40.0)
            ParticleTool.sendParticle(
                serverLevel,
                particleData,
                pos.x + 0.05 * i * dir.x,
                pos.y + 0.05 * i * dir.y,
                pos.z + 0.05 * i * dir.z,
                0,
                vec3.x,
                vec3.y,
                vec3.z,
                10.0,
                true
            )
        }
        for (i in 0..2) {
            val vec3 = randomVec(dir, 20.0)
            ParticleTool.sendParticle(
                serverLevel,
                ParticleTypes.SMOKE,
                pos.x,
                pos.y,
                pos.z,
                0,
                vec3.x,
                vec3.y,
                vec3.z,
                0.05,
                true
            )
        }
        val soundType = state.soundType
        if (soundType === SoundType.METAL || soundType === SoundType.ANVIL || soundType === SoundType.CHAIN || soundType === SoundType.COPPER || soundType === SoundType.NETHERITE_BLOCK) {
            serverLevel.playSound(null, pos.x, pos.y, pos.z, ModSounds.HIT.get(), SoundSource.BLOCKS, 2f, 1f)
            for (i in 0..2) {
                val vec3 = randomVec(dir, 80.0)
                ParticleTool.sendParticle(
                    serverLevel,
                    ModParticleTypes.FIRE_STAR.get(),
                    pos.x,
                    pos.y,
                    pos.z,
                    0,
                    vec3.x,
                    vec3.y,
                    vec3.z,
                    0.2 + 0.1 * Math.random(),
                    true
                )
            }
        }
    }

    fun randomVec(vec3: Vec3, spread: Double): Vec3 {
        return vec3.normalize().add(
            this.random.triangle(0.0, 0.0172275 * spread),
            this.random.triangle(0.0, 0.0172275 * spread),
            this.random.triangle(0.0, 0.0172275 * spread)
        )
    }

    protected fun onHitEntity(entity: Entity?, result: ExtendedEntityRayTraceResult) {
        var entity = entity ?: return

        val headshot = result.headshot
        val legShot = result.legShot

        if (entity is PartEntity<*>) {
            entity = entity.getParent()
        }

        if (entity is LivingEntity) {
            entity.level().playSound(
                null,
                entity.onPos,
                ModSounds.MELEE_HIT.get(),
                SoundSource.PLAYERS,
                1f,
                (2 * Math.random() - 1).toFloat() * 0.1f + 1.0f
            )

            if (beast) {
                beastKill(this.shooter, entity)
                return
            }
        }

        this.damage *= (deltaMovement.length() / velocity).coerceIn(0.0, 1.0).toFloat()

        val shooter = this.shooter
        if (headshot) {
            if (shooter is ServerPlayer) {
                val holder = Holder.direct(ModSounds.HEADSHOT.get())
                sendPacketTo(
                    shooter, ClientboundSoundPacket(
                        holder,
                        SoundSource.PLAYERS,
                        shooter.x,
                        shooter.y,
                        shooter.z,
                        1f,
                        1f,
                        shooter.level().random.nextLong()
                    )
                )
                sendPacketTo(shooter, ClientIndicatorMessage(1, 5))
            }
            performOnHit(entity, this.damage, true, this.knockback.toDouble())
        } else {
            if (shooter is ServerPlayer) {
                val holder = Holder.direct(ModSounds.INDICATION.get())
                sendPacketTo(
                    shooter, ClientboundSoundPacket(
                        holder,
                        SoundSource.PLAYERS,
                        shooter.x,
                        shooter.y,
                        shooter.z,
                        1f,
                        1f,
                        shooter.level().random.nextLong()
                    )
                )
                sendPacketTo(shooter, ClientIndicatorMessage(0, 5))
            }

            if (legShot) {
                if (entity is LivingEntity) {
                    if (entity is Player && entity.isCreative) {
                        return
                    }
                    if (!entity.level().isClientSide()) {
                        entity.addEffect(MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20, 2, false, false))
                    }
                }
                this.damage *= this.legShot
            }

            performOnHit(entity, this.damage, false, this.knockback.toDouble())
        }

        if (!this.mobEffects.isEmpty() && entity is LivingEntity) {
            for (instance in this.mobEffects) {
                entity.addEffect(instance, this.shooter)
            }
        }

        this.discard()
    }

    open fun performOnHit(entity: Entity, damage: Float, headshot: Boolean, knockback: Double) {
        if (entity is LivingEntity) {
            if (this.forceKnockback) {
                val vec3 = this.deltaMovement.multiply(1.0, 0.0, 1.0).normalize()
                entity.addDeltaMovement(vec3.scale(knockback))
                performDamage(entity, damage, headshot)
            } else {
                val iCustomKnockback = ICustomKnockback.getInstance(entity)
                iCustomKnockback.`superbWarfare$setKnockbackStrength`(knockback)
                performDamage(entity, damage, headshot)
                iCustomKnockback.`superbWarfare$resetKnockbackStrength`()
            }
        } else {
            performDamage(entity, damage, headshot)
        }
    }

    /** Legacy: explosion damage and no resolver suppression. TNT: once per round, even when armor owns the hit. */
    private fun shouldExplode(resolution: ProjectileImpactResult): Boolean {
        if (TntBlast.active(this)) return !tntDetonated
        return this.explosionDamage > 0 && !resolution.suppressesDefaultExplosion()
    }

    protected fun explosionBullet(projectile: Entity, hitVec: Vec3) {
        val kg = TntEquivalents.resolve(projectile)
        if (TntBlast.active(kg)) {
            if (tntDetonated) return
            tntDetonated = true
        }
        CustomExplosion.Builder(projectile)
            .attacker(this.shooter)
            .damage(this.explosionDamage)
            .radius(this.explosionRadius)
            .position(hitVec)
            .emitFx(activeImpactResult?.suppressesDefaultVisuals() != true)
            .tntEquivalent(kg)
            .explode()
    }

    override fun suppressesNativeVehicleModuleDamage(): Boolean {
        return activeImpactResult?.suppressesNativeModuleDamage() == true
    }

    override fun setDamage(damage: Float) {
        this.damage = damage
    }

    fun getDamage(): Float {
        return this.damage
    }

    open fun shoot(living: LivingEntity?, vecX: Double, vecY: Double, vecZ: Double, velocity: Float, spread: Float) {
        val vec3 = Vec3(vecX, vecY, vecZ).normalize()
            .add(
                this.random.triangle(0.0, 0.0172275 * spread.toDouble()),
                this.random.triangle(0.0, 0.0172275 * spread.toDouble()),
                this.random.triangle(0.0, 0.0172275 * spread.toDouble())
            ).scale(velocity.toDouble())
        this.deltaMovement = vec3
        val d0 = vec3.horizontalDistance()
        this.yRot = (Mth.atan2(vec3.x, vec3.z) * (180f / PI.toFloat()).toDouble()).toFloat()
        this.xRot = (Mth.atan2(vec3.y, d0) * (180f / PI.toFloat()).toDouble()).toFloat()
        this.yRotO = this.yRot
        this.xRotO = this.xRot
    }

    open fun updateHeading() {
        val horizontalDistance = this.deltaMovement.horizontalDistance()
        this.yRot = (Mth.atan2(
            this.deltaMovement.x(),
            this.deltaMovement.z()
        ) * (180.0 / PI)).toFloat()
        this.xRot = (Mth.atan2(this.deltaMovement.y(), horizontalDistance) * (180.0 / PI)).toFloat()
        this.yRotO = this.yRot
        this.xRotO = this.xRot
    }

    private fun performDamage(entity: Entity, damage: Float, isHeadshot: Boolean) {
        val rate = this.bypassArmorRate.coerceIn(0f, 1f)

        val normalDamage = damage * (1 - rate).coerceIn(0f, 1f)
        val absoluteDamage = damage * rate.coerceIn(0f, 1f)

        entity.invulnerableTime = 0

        val headShotModifier = if (isHeadshot) this.headShot else 1f
        // 先造成穿甲伤害
        if (absoluteDamage > 0) {
            entity.forceHurt(
                if (isHeadshot)
                    causeGunFireHeadshotAbsoluteDamage(this.level().registryAccess(), this, this.shooter)
                else
                    causeGunFireAbsoluteDamage(this.level().registryAccess(), this, this.shooter),
                absoluteDamage * headShotModifier
            )
            entity.invulnerableTime = 0

            // 大于1的穿甲对载具造成额外伤害
            if (entity is VehicleEntity && this.bypassArmorRate > 1) {
                entity.hurt(
                    causeGunFireAbsoluteDamage(this.level().registryAccess(), this, this.shooter),
                    (absoluteDamage * (this.bypassArmorRate - 1) * 0.5f *
                        com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponDamagePolicy.scale(this)).toFloat()
                )
            }
        }
        if (normalDamage > 0) {
            entity.forceHurt(
                if (isHeadshot)
                    causeGunFireHeadshotDamage(this.level().registryAccess(), this, this.shooter)
                else
                    causeGunFireDamage(this.level().registryAccess(), this, this.shooter),
                normalDamage * headShotModifier
            )
            entity.invulnerableTime = 0
        }
    }

    override fun setGravity(gravity: Float) {
        this.gravity = gravity
    }

    override fun setExplosionDamage(explosionDamage: Float) {
        this.explosionDamage = explosionDamage
    }

    override fun setExplosionRadius(radius: Float) {
        this.explosionRadius = radius
    }

    /**
     * Builders
     */
    fun shooter(shooter: Entity?): ProjectileEntity {
        this.shooter = shooter
        this.owner = shooter
        return this
    }

    fun damage(damage: Float): ProjectileEntity {
        this.damage = damage
        return this
    }

    fun velocity(velocity: Float): ProjectileEntity {
        this.velocity = velocity
        return this
    }

    fun headShot(headShot: Float): ProjectileEntity {
        this.headShot = headShot
        return this
    }

    fun legShot(legShot: Float): ProjectileEntity {
        this.legShot = legShot
        return this
    }

    fun beast(): ProjectileEntity {
        this.beast = true
        return this
    }

    fun fireBullet(fireLevel: Int, dragonBreath: Boolean): ProjectileEntity {
        this.fireLevel = fireLevel
        this.dragonBreath = dragonBreath
        return this
    }

    fun zoom(zoom: Boolean): ProjectileEntity {
        this.isZoom = zoom
        return this
    }

    fun bypassArmorRate(bypassArmorRate: Float): ProjectileEntity {
        this.bypassArmorRate = bypassArmorRate
        return this
    }

    fun effect(mobEffectInstances: List<MobEffectInstance>): ProjectileEntity {
        this.mobEffects.addAll(mobEffectInstances)
        return this
    }

    fun setRGB(rgb: FloatArray) {
        this.entityData.set(COLOR_R, rgb[0])
        this.entityData.set(COLOR_G, rgb[1])
        this.entityData.set(COLOR_B, rgb[2])
    }

    fun knockback(knockback: Float): ProjectileEntity {
        this.knockback = knockback
        return this
    }

    fun forceKnockback(): ProjectileEntity {
        this.forceKnockback = true
        return this
    }

    fun setGunItemId(stack: ItemStack): ProjectileEntity {
        this.gunItemId = stack.descriptionId
        return this
    }

    fun setGunItemId(id: String?): ProjectileEntity {
        this.gunItemId = id
        return this
    }

    /** Preserves legacy identity while preventing its server-side gameplay lifecycle. */
    fun markImpactShrapnel(): ProjectileEntity {
        this.impactShrapnel = true
        this.entityData.set(IMPACT_SHRAPNEL, true)
        this.entityData.set(IMPACT_SHRAPNEL_LIFE, life.coerceIn(1, 16))
        retireImpactShrapnel()
        return this
    }

    private fun retireImpactShrapnel(): Boolean {
        if (level().isClientSide || !isImpactShrapnel()) return false
        discard()
        return true
    }

    fun isImpactShrapnel(): Boolean {
        return this.impactShrapnel || this.entityData.get(IMPACT_SHRAPNEL)
    }

    fun impactShrapnelLifetime(): Int = entityData.get(IMPACT_SHRAPNEL_LIFE)

    override fun setLife(life: Int) {
        this.life = life
        if (isImpactShrapnel()) entityData.set(IMPACT_SHRAPNEL_LIFE, life.coerceIn(1, 16))
    }

    companion object {
        private const val PROJECTILE_DATA_TAG = "SbwProjectileData"

        @JvmField
        val COLOR_R: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ProjectileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val COLOR_G: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ProjectileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val COLOR_B: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(ProjectileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val IMPACT_SHRAPNEL: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(ProjectileEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val IMPACT_SHRAPNEL_LIFE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(ProjectileEntity::class.java, EntityDataSerializers.INT)

        private val PROJECTILE_TARGETS =
            Predicate { input: Entity? -> input != null && input.isPickable && !input.isSpectator && input.isAlive }
        private val IGNORE_LIST = Predicate { input: BlockState ->
            input.`is`(ModTags.Blocks.BULLET_IGNORE) && !(input.`is`(Blocks.IRON_DOOR) || input.`is`(Blocks.IRON_TRAPDOOR))
        }

        // 子弹的颜色
        const val DEFAULT_R: Float = 1.0f
        const val DEFAULT_G: Float = 222 / 255f
        const val DEFAULT_B: Float = 39 / 255f

        @JvmStatic
        fun rayTraceNominalBlocks(world: Level, context: ClipContext): BlockHitResult =
            rayTraceBlocks(world, context, nominalBlockIgnorePredicate())

        /** One-cell form used by the non-loading nominal predictor's matching voxel traversal. */
        @JvmStatic
        fun rayTraceNominalCell(world: Level, context: ClipContext, blockPos: BlockPos): BlockHitResult? =
            rayTraceCell(world, context, blockPos, nominalBlockIgnorePredicate())

        /**
         * Predictor-only one-cell clip after the caller has applied [isNominalBlockIgnored].
         * Reuses the DDA's already-fetched states without changing the live projectile entry.
         */
        @JvmStatic
        internal fun rayTraceAcceptedNominalCell(
            world: Level,
            context: ClipContext,
            blockPos: BlockPos,
            blockState: BlockState,
            fluidState: FluidState,
        ): BlockHitResult? = clipAcceptedCell(world, context, blockPos, blockState, fluidState)

        @JvmStatic
        fun isNominalBlockIgnored(blockState: BlockState): Boolean =
            nominalBlockIgnorePredicate().test(blockState)

        private fun nominalBlockIgnorePredicate(): Predicate<BlockState> =
            if (ProjectileConfig.PROJECTILE_DESTROY_BLOCKS.get()) {
                IGNORE_LIST.and(Predicate { input -> !input.`is`(ModTags.Blocks.BULLET_CAN_DESTROY) })
            } else IGNORE_LIST

        @JvmStatic
        fun rayTraceBlocks(
            world: Level,
            context: ClipContext,
            ignorePredicate: Predicate<BlockState>
        ): BlockHitResult {
            return performRayTrace(
                context, { rayTraceContext, blockPos -> rayTraceCell(world, rayTraceContext, blockPos, ignorePredicate) },
                { rayTraceContext ->
                    val vec3 = rayTraceContext.from.subtract(rayTraceContext.to)
                    BlockHitResult.miss(
                        rayTraceContext.to,
                        Direction.getNearest(vec3.x, vec3.y, vec3.z),
                        BlockPos.containing(rayTraceContext.to)
                    )
                })
        }

        private fun rayTraceCell(
            world: Level,
            context: ClipContext,
            blockPos: BlockPos,
            ignorePredicate: Predicate<BlockState>,
        ): BlockHitResult? {
            val blockState = world.getBlockState(blockPos)
            if (ignorePredicate.test(blockState)) return null
            return clipAcceptedCell(world, context, blockPos, blockState, world.getFluidState(blockPos))
        }

        private fun clipAcceptedCell(
            world: Level,
            context: ClipContext,
            blockPos: BlockPos,
            blockState: BlockState,
            fluidState: FluidState,
        ): BlockHitResult? {
            val startVec = context.from
            val endVec = context.to
            val blockShape = context.getBlockShape(blockState, world, blockPos)
            val blockResult = world.clipWithInteractionOverride(startVec, endVec, blockPos, blockShape, blockState)
            val fluidShape = context.getFluidShape(fluidState, world, blockPos)
            val fluidResult = fluidShape.clip(startVec, endVec, blockPos)
            val blockDistance = blockResult?.location?.let(context.from::distanceToSqr) ?: Double.MAX_VALUE
            val fluidDistance = fluidResult?.location?.let(context.from::distanceToSqr) ?: Double.MAX_VALUE
            return if (blockDistance <= fluidDistance) blockResult else fluidResult
        }

        private fun <T> performRayTrace(
            context: ClipContext,
            hitFunction: BiFunction<ClipContext, BlockPos, T?>,
            function: Function<ClipContext, T>
        ): T {
            val startVec = context.from
            val endVec = context.to
            if (startVec != endVec) {
                val startX = Mth.lerp(-0.0000001, endVec.x, startVec.x)
                val startY = Mth.lerp(-0.0000001, endVec.y, startVec.y)
                val startZ = Mth.lerp(-0.0000001, endVec.z, startVec.z)
                val endX = Mth.lerp(-0.0000001, startVec.x, endVec.x)
                val endY = Mth.lerp(-0.0000001, startVec.y, endVec.y)
                val endZ = Mth.lerp(-0.0000001, startVec.z, endVec.z)
                var blockX = Mth.floor(endX)
                var blockY = Mth.floor(endY)
                var blockZ = Mth.floor(endZ)
                val mutablePos = MutableBlockPos(blockX, blockY, blockZ)
                val t = hitFunction.apply(context, mutablePos)
                if (t != null) {
                    return t
                }

                val deltaX = startX - endX
                val deltaY = startY - endY
                val deltaZ = startZ - endZ
                val signX = Mth.sign(deltaX)
                val signY = Mth.sign(deltaY)
                val signZ = Mth.sign(deltaZ)
                val d9 = if (signX == 0) Double.MAX_VALUE else signX.toDouble() / deltaX
                val d10 = if (signY == 0) Double.MAX_VALUE else signY.toDouble() / deltaY
                val d11 = if (signZ == 0) Double.MAX_VALUE else signZ.toDouble() / deltaZ
                var d12 = d9 * (if (signX > 0) 1 - Mth.frac(endX) else Mth.frac(endX))
                var d13 = d10 * (if (signY > 0) 1 - Mth.frac(endY) else Mth.frac(endY))
                var d14 = d11 * (if (signZ > 0) 1 - Mth.frac(endZ) else Mth.frac(endZ))

                while (d12 <= 1 || d13 <= 1 || d14 <= 1) {
                    if (d12 < d13) {
                        if (d12 < d14) {
                            blockX += signX
                            d12 += d9
                        } else {
                            blockZ += signZ
                            d14 += d11
                        }
                    } else if (d13 < d14) {
                        blockY += signY
                        d13 += d10
                    } else {
                        blockZ += signZ
                        d14 += d11
                    }

                    val t1 = hitFunction.apply(context, mutablePos.set(blockX, blockY, blockZ))
                    if (t1 != null) {
                        return t1
                    }
                }
            }
            return function.apply(context)
        }
    }
}
