package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion

import com.atsuishio.superbwarfare.Mod.Companion.queueServerWork
import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent.HitBlock
import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent.HitEntity
import com.atsuishio.superbwarfare.api.projectile.BallisticSync
import com.atsuishio.superbwarfare.api.projectile.ProfiledProjectile
import com.atsuishio.superbwarfare.api.projectile.SmoothedBallisticProjectile
import com.atsuishio.superbwarfare.api.vehicle.weapon.AircraftRoundConsolidation
import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess
import com.atsuishio.superbwarfare.api.projectile.FarProjectileSimulation
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailKind
import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailProviders
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile
import com.atsuishio.superbwarfare.api.projectile.SequencedProjectile
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDamagePolicy
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResolver
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.config.server.ExplosionConfig
import com.atsuishio.superbwarfare.config.server.ProjectileConfig
import com.atsuishio.superbwarfare.init.ModDamageTypes.causeProjectileHitDamage
import com.atsuishio.superbwarfare.network.message.receive.ClientMotionSyncMessage
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.tools.CustomExplosion
import com.atsuishio.superbwarfare.tools.ParticleTool
import com.atsuishio.superbwarfare.tools.blast.TntBlast
import com.atsuishio.superbwarfare.tools.blast.TntEquivalents
import com.atsuishio.superbwarfare.tools.postEvent
import com.atsuishio.superbwarfare.tools.sendPacketToTrackingThis
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.TicketType
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.projectile.ThrowableItemProjectile
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.entity.IEntityAdditionalSpawnData
import net.minecraftforge.network.NetworkHooks
import java.util.function.Consumer

abstract class FastThrowableProjectile : ThrowableItemProjectile, CustomSyncMotionEntity, IEntityAdditionalSpawnData,
    ExplosiveProjectile, ProfiledProjectile, SequencedProjectile, ProjectileImpactDamagePolicy, FarProjectileAccess,
    SmoothedBallisticProjectile {
    override fun farProjectileExplosionRadius(): Double =
        maxOf(explosionRadiusValue.toDouble(), TntBlast.farQueryRadius(this))
    override fun farProjectileLifetimeTicks(): Int = getLife().coerceIn(0, 2399) + 1
    override fun farProjectileTerminatesNextTick(currentAge: Int): Boolean = currentAge >= getLife()
    /** Server-to-client correction policy for deterministic and guided projectiles. */
    enum class MotionSyncMode {
        NONE,
        ENTITY_INTERVAL,
        EVERY_TICK
    }

    private var _projectileProfileId: net.minecraft.resources.ResourceLocation? = null
    private var _resolvedProjectileProfile: ResolvedProjectileProfile? = null
    private var _projectileShotSequence = 0L
    private var _projectileDimensions: EntityDimensions? = null
    private var activeImpactResult: ProjectileImpactResult? = null
    private var motionSyncPending = false
    private var pendingMotionSyncTick = 0
    /** Server: state at the start of the current step, compared with the clients' prediction. */
    private var ballisticStartPosition: Vec3? = null
    private var ballisticStartVelocity: Vec3 = Vec3.ZERO
    /** Client: outstanding step-aligned correction, blended over a few ticks. */
    private val ballisticCorrection = BallisticSync.Correction()
    /** Client: spawn data (exact velocity) has been applied; later tracker velocity is clamped. */
    private var ballisticSpawned = false

    var damageValue: Float = 0f
    var explosionDamageValue: Float = 0f
    var explosionRadiusValue: Float = 0f
    var gravityValue: Float = 0.05f
    var lifeValue: Int = 400
    var durability: Int = 50
    var firstHit: Boolean = true

    private var isFastMoving = false

    var exploded: Boolean = false

    /**
     * Legacy addon bridge to the entity age in ticks, also used by projectile renderers.
     * Mutates this entity only; callers must use its owning thread. Display addons should
     * call this on their detached preview projectile, not a live projectile.
     */
    fun setSyncedTick(value: Int) {
        tickCount = value
    }

    constructor(pEntityType: EntityType<out ThrowableItemProjectile>, pLevel: Level) : super(pEntityType, pLevel)

    constructor(
        pEntityType: EntityType<out ThrowableItemProjectile>,
        pX: Double,
        pY: Double,
        pZ: Double,
        pLevel: Level
    ) : super(pEntityType, pX, pY, pZ, pLevel)

    constructor(pEntityType: EntityType<out ThrowableItemProjectile>, pShooter: Entity?, pLevel: Level) : super(
        pEntityType,
        pLevel
    ) {
        this.owner = pShooter
        if (pShooter != null) {
            this.setPos(pShooter.x, pShooter.eyeY - 0.1, pShooter.z)
        }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        compound.readIfPresent("Damage") { damageValue = getFloat(it) }
        compound.readIfPresent("ExplosionDamage") { explosionDamageValue = getFloat(it) }
        compound.readIfPresent("Radius") { explosionRadiusValue = getFloat(it) }
        compound.readIfPresent("Durability") { durability = getInt(it) }
        compound.readIfPresent("Life") { lifeValue = getInt(it) }
        compound.readIfPresent("Gravity") { gravityValue = getFloat(it) }
        compound.readIfPresent("FirstHit") { firstHit = getBoolean(it) }
        compound.readIfPresent("Exploded") { exploded = getBoolean(it) }
        compound.readIfPresent("SbwProjectileAge") { tickCount = getInt(it).coerceAtLeast(0) }
        ProjectileProfiles.readAdditionalSaveData(this, compound)
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)

        if (this.damageValue > 0) {
            compound.putFloat("Damage", this.damageValue)
        }
        if (this.explosionDamageValue > 0) {
            compound.putFloat("ExplosionDamage", this.explosionDamageValue)
        }
        if (this.explosionRadiusValue > 0) {
            compound.putFloat("Radius", this.explosionRadiusValue)
        }
        if (this.durability > 0) {
            compound.putInt("Durability", this.durability)
        }
        if (this.lifeValue > 0) {
            compound.putInt("Life", this.lifeValue)
        }
        compound.putFloat("Gravity", this.gravityValue)
        compound.putBoolean("FirstHit", this.firstHit)
        compound.putBoolean("Exploded", this.exploded)
        compound.putInt("SbwProjectileAge", this.tickCount.coerceAtLeast(0))
        ProjectileProfiles.writeAdditionalSaveData(this, compound)
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

    override fun tick() {
        motionSyncPending = false
        ballisticStartPosition = null
        if (!level().isClientSide && smoothsBallisticFlight()) {
            ballisticStartPosition = position()
            ballisticStartVelocity = deltaMovement
        }
        com.atsuishio.superbwarfare.diagnostics.EliteVehicleDiagnostics.projectile(this)
        super.tick()

        if (!this.isFastMoving && this.isFastMoving() && this.level().isClientSide) {
            playFlySound.accept(this)
            playNearFlySound.accept(this)
        }
        this.isFastMoving = this.isFastMoving()

        val friction = if (this.isInWater) {
            0.8f
        } else {
            0.99f
        }

        // 撤销重力影响
        // 重新计算动量
        this.deltaMovement = NominalProjectileMotion.afterVanillaThrowableStep(
            this.deltaMovement,
            this.gravity.toDouble(),
            friction,
            this.gravity.toDouble(),
        )

        // 重新应用重力

        if (level().isClientSide && !isRemoved) {
            ballisticCorrection.next()?.let { this.setPos(this.x + it.x, this.y + it.y, this.z + it.z) }
        }

        // 同步动量
        if (!isRemoved) {
            if (deferTickSynchronization()) {
                pendingMotionSyncTick = tickCount
                motionSyncPending = true
            } else {
                this.syncMotion()
            }
        }

        // 更新区块加载位置
        if (level() is ServerLevel) {
            if (!FarProjectileSimulation.isSupplementalTick(this) && forceLoadChunk() && ProjectileConfig.PROJECTILE_CHUNK_LOADING.get()) {
                this.keepChunkLoaded(this.position())
                this.keepChunkLoaded(position().add(this.deltaMovement.normalize().scale(16.0)))
            }

            if (tickCount > getLife()) {
                if (explosionRadiusValue > 0) {
                    causeExplode(position())
                }
                this.discard()
            }
        }
    }

    override fun updateRotation() {
        val vec3 = this.deltaMovement
        val d0 = vec3.horizontalDistance()
        this.xRot = lerpRotation(
            this.xRotO,
            -(Mth.atan2(vec3.y, d0) * (180f / Math.PI.toFloat()).toDouble()).toFloat()
        )
        this.yRot = lerpRotation(
            this.yRotO,
            -(Mth.atan2(vec3.x, vec3.z) * (180f / Math.PI.toFloat()).toDouble()).toFloat()
        )
    }

    /** Carrier payloads enter the same event, armor, native continuation and disposal path. */
    fun impactFromCarrier(result: EntityHitResult) {
        if (!level().isClientSide && !isRemoved) onHit(result)
    }

    override fun onHit(result: HitResult) {
        if (result.type == HitResult.Type.MISS) {
            return
        }
        if (level().isClientSide) {
            // Impact resolvers are server-authoritative. Preserve vanilla client
            // prediction without invoking handlers that may mutate gameplay.
            super.onHit(result)
            return
        }
        val legacyEventCancelled = when (result) {
            is EntityHitResult -> postEvent(HitEntity(owner, this, result.entity, result.location))
            is BlockHitResult -> postEvent(
                HitBlock(
                    result.blockPos,
                    level().getBlockState(result.blockPos),
                    result.direction,
                    owner,
                    this,
                    result.location
                )
            )
            else -> false
        }
        if (legacyEventCancelled) {
            return
        }
        val context = when (result) {
            is EntityHitResult -> ProjectileImpactContext.entity(
                this.owner,
                this,
                result.entity,
                result.location,
                causeProjectileHitDamage(this.level().registryAccess(), this, this.owner),
            )
            is BlockHitResult -> ProjectileImpactContext.block(
                this.owner,
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
            if (resolution.consumesProjectile()) {
                // An addon armor resolver owns the direct hit; a TNT-equivalent charge still detonates at the
                // impact point for nearby infantry and vehicles (including the struck hull for >= 25 kg).
                detonateResolvedImpact(result.location, resolution)
                this.discard()
            }
            return
        }

        resolution.residualDamage?.let { this.damageValue = it }
        resolution.residualExplosionDamage?.let { this.explosionDamageValue = it }
        resolution.residualExplosionRadius?.let { this.explosionRadiusValue = it }

        activeImpactResult = resolution
        try {
            super.onHit(result)
        } finally {
            activeImpactResult = null
        }
    }

    open fun destroyBlock(blockHitResult: BlockHitResult) {
        val resultPos = blockHitResult.blockPos
        val hardness = this.level().getBlockState(resultPos).block.defaultDestroyTime()
        if (hardness != -1f) {
            if (ExplosionConfig.explosionsBreakBlocks()) {
                if (firstHit) {
                    causeExplode(blockHitResult.getLocation())
                    firstHit = false
                    queueServerWork(3) { this.discard() }
                }
                if (ExplosionConfig.EXTRA_EXPLOSION_EFFECT.get()
                    && !ProjectileProfiles.suppressesVehicleBlockDamage(this)
                ) {
                    this.level().destroyBlock(resultPos, true)
                }
            }
        } else {
            causeExplode(blockHitResult.getLocation())
            this.discard()
        }
        if (!ExplosionConfig.explosionsBreakBlocks()) {
            causeExplode(blockHitResult.getLocation())
            this.discard()
        }
    }

    protected fun blockPenetrationResistance(result: BlockHitResult): Double? {
        if (!ExplosionConfig.explosionsBreakBlocks()) {
            destroyBlock(result)
            return null
        }

        val level = level()
        val pos = result.blockPos
        val blockState = level.getBlockState(pos)
        val hardness = blockState.block.defaultDestroyTime()
        val resistance = 0.95 - (hardness / 100).coerceIn(0f, 1f)

        if (blockState.canOcclude() || blockState.soundType === SoundType.GLASS) {
            durability -= 5 + hardness.toInt()
        }
        if (blockState.soundType === SoundType.STONE) {
            durability -= 5
        }
        if (blockState.soundType === SoundType.METAL ||
            blockState.soundType === SoundType.COPPER ||
            blockState.soundType === SoundType.NETHERITE_BLOCK
        ) {
            durability -= 25
        }

        if (hardness <= durability && hardness != -1f
            && !ProjectileProfiles.suppressesVehicleBlockDamage(this)
        ) {
            level.destroyBlock(pos, true)
        }
        if (hardness == -1f || hardness > durability || durability <= 0) {
            causeExplode(pos.center)
            discard()
            return null
        }
        return resistance
    }

    /** TNT-equivalent blast for a direct hit whose resolver consumed the projectile; legacy rounds stay silent. */
    private fun detonateResolvedImpact(location: Vec3, resolution: ProjectileImpactResult) {
        if (exploded || !TntBlast.active(this)) return
        exploded = true
        activeImpactResult = resolution
        try {
            buildExplosion(location).explode()
        } finally {
            activeImpactResult = null
        }
    }

    protected fun <T : FastThrowableProjectile> inheritPenetrationState(
        target: T,
        resistance: Double,
        configureSubtype: T.() -> Unit,
    ): T {
        target.owner = owner
        target.durability = durability
        target.configureSubtype()
        target.setGravity(gravityValue)
        target.setLife(lifeValue - tickCount)
        target.setDamage((damageValue * resistance).toFloat())
        target.setExplosionDamage((explosionDamageValue * resistance).toFloat())
        target.setExplosionRadius((explosionRadiusValue * resistance).toFloat())
        ProjectileProfiles.copy(this, target)
        // The charge itself is not consumed by block penetration.
        TntEquivalents.copy(this, target)
        return target
    }

    open fun buildExplosion(vec3: Vec3): CustomExplosion.Builder {
        val resolvedExplosionDamage = activeImpactResult?.residualExplosionDamage ?: explosionDamageValue
        val resolvedExplosionRadius = activeImpactResult?.residualExplosionRadius ?: explosionRadiusValue
        return CustomExplosion.Builder(this)
            .attacker(this.owner)
            .damage(resolvedExplosionDamage)
            .radius(resolvedExplosionRadius)
            .position(vec3)
            .withParticleType(explosionParticleType(resolvedExplosionRadius))
            .emitFx(shouldEmitDefaultImpactFx())
            .tntEquivalent(TntEquivalents.resolve(this))
    }

    open fun causeExplode(vec3: Vec3) {
        if (!exploded) {
            exploded = true
            // A resolver may suppress the legacy blast (armor owns the hit); a TNT-equivalent charge still detonates.
            if (activeImpactResult?.suppressesDefaultExplosion() != true || TntBlast.active(this)) {
                buildExplosion(vec3).explode()
            }
        }

        if (discardAfterExplode()) {
            this.discard()
        }
    }

    protected fun shouldEmitDefaultImpactFx(): Boolean {
        return activeImpactResult?.suppressesDefaultVisuals() != true
    }

    override fun suppressesNativeVehicleModuleDamage(): Boolean {
        return activeImpactResult?.suppressesNativeModuleDamage() == true
    }

    open fun explosionParticleType(radius: Float): ParticleTool.ParticleType {
        return if (radius < 2.0) {
            ParticleTool.ParticleType.MINI
        } else if (radius in 2.0..<4.0) {
            ParticleTool.ParticleType.SMALL
        } else if (radius in 4.0..<7.0) {
            ParticleTool.ParticleType.MEDIUM
        } else if (radius in 7.0..<10.0) {
            ParticleTool.ParticleType.LARGE
        } else if (radius in 10.0..<20.0) {
            ParticleTool.ParticleType.HUGE
        } else {
            ParticleTool.ParticleType.GIANT
        }
    }

    open fun discardAfterExplode(): Boolean {
        return false
    }

    open fun keepChunkLoaded(position: Vec3) {
        val chunkPos = ChunkPos(BlockPos.containing(position))
        NetworkTelemetry.recordSystemWork("chunk_ticket.projectile_refresh")
        (level() as ServerLevel).chunkSource.addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 3, this.id)
    }

    /** Opt-in for subclasses whose tick commits motion/stage after the superclass returns. */
    protected open fun deferTickSynchronization(): Boolean = false

    /**
     * Publishes only this tick's queued correction after the subclass has committed its state.
     * Repeated calls, missing superclass ticks and removed projectiles cannot publish again.
     */
    protected open fun commitTickSynchronization() {
        if (!motionSyncPending) return
        motionSyncPending = false
        if (pendingMotionSyncTick != tickCount || isRemoved) return
        this.syncMotion()
    }

    override fun syncMotion() {
        if (this.level().isClientSide || isRemoved) return
        val mode = motionSyncMode()
        if (mode == MotionSyncMode.EVERY_TICK && smoothsBallisticFlight()) {
            // Smoothed clients only accept step-aligned states; a legacy motion message would be ignored.
            ballisticStartPosition = null
            NetworkTelemetry.recordSystemWork("projectile.motion_correction")
            sendPacketToTrackingThis(ClientMotionSyncMessage.ballistic(this))
            return
        }
        when (mode) {
            MotionSyncMode.NONE -> return
            MotionSyncMode.ENTITY_INTERVAL -> {
                if (smoothsBallisticFlight()) {
                    // Clients run the same step; publish only a departure from it or a sparse realignment.
                    val start = ballisticStartPosition
                    ballisticStartPosition = null
                    val deviated = start == null || BallisticSync.deviates(start, ballisticStartVelocity,
                        position(), deltaMovement, ballisticStep(ballisticStartVelocity))
                    if (!deviated && this.tickCount % BallisticSync.CORRECTION_INTERVAL_TICKS != 0) return
                    NetworkTelemetry.recordSystemWork("projectile.motion_correction")
                    sendPacketToTrackingThis(ClientMotionSyncMessage.ballistic(this))
                    return
                }
                if (this.tickCount % this.type.updateInterval() != 0) return
            }
            MotionSyncMode.EVERY_TICK -> Unit
        }

        NetworkTelemetry.recordSystemWork("projectile.motion_correction")
        sendPacketToTrackingThis(ClientMotionSyncMessage(this))
    }

    /**
     * Subclasses should select a policy instead of replacing [syncMotion].
     * The legacy hook remains part of the default for addon compatibility.
     */
    open fun motionSyncMode(): MotionSyncMode {
        if (!shouldSyncMotion()) return MotionSyncMode.NONE
        return ProjectileProfiles.motionSyncMode(this, MotionSyncMode.ENTITY_INTERVAL)
    }

    open fun isFastMoving(): Boolean {
        return this.deltaMovement.length() >= 0.5
    }

    /** Opt-in for unguided rounds whose whole flight is the deterministic air step below. */
    override fun smoothsBallisticFlight(): Boolean = false

    override fun ballisticStep(velocity: Vec3): Vec3 =
        NominalProjectileMotion.afterFastThrowableAirStep(velocity, this.gravity.toDouble())

    override fun acceptBallisticState(tick: Int, position: Vec3, velocity: Vec3) {
        if (!level().isClientSide || isRemoved) return
        val gravity = this.gravity.toDouble()
        val dragProduct = 0.99f.toDouble() * (1f / 0.99f).toDouble()
        val aligned = BallisticSync.align(tick, tickCount, position, velocity, this::ballisticStep) {
            it.add(0.0, gravity, 0.0).scale(1.0 / dragProduct)
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

    open fun shouldSyncMotion(): Boolean {
        return true
    }

    override fun writeSpawnData(buffer: FriendlyByteBuf) {
        val motion = this.deltaMovement
        buffer.writeFloat(motion.x.toFloat())
        buffer.writeFloat(motion.y.toFloat())
        buffer.writeFloat(motion.z.toFloat())
        buffer.writeVarInt(this.tickCount)
        // Client flight uses the authored drop per tick, not the constructor default.
        buffer.writeFloat(this.gravityValue)
        buffer.writeVarInt(AircraftRoundConsolidation.weight(this))
        ProjectileProfiles.writeSpawnData(this, buffer)
    }

    override fun readSpawnData(additionalData: FriendlyByteBuf) {
        this.setDeltaMovement(
            additionalData.readFloat().toDouble(),
            additionalData.readFloat().toDouble(),
            additionalData.readFloat().toDouble()
        )
        this.tickCount = additionalData.readVarInt()
        val syncedGravity = additionalData.readFloat()
        if (syncedGravity.isFinite()) this.gravityValue = syncedGravity
        AircraftRoundConsolidation.mark(this, additionalData.readVarInt())
        ballisticSpawned = true
        ProjectileProfiles.readSpawnData(this, additionalData)
    }

    open fun getSound(): SoundEvent = SoundEvents.EMPTY

    open fun getVolume(): Float = 0.5f

    open fun forceLoadChunk(): Boolean {
        return false
    }

    override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
        return NetworkHooks.getEntitySpawningPacket(this)
    }

    override fun shouldRenderAtSqrDistance(pDistance: Double): Boolean {
        return true
    }

    override fun setDamage(damage: Float) {
        this.damageValue = damage
    }

    override fun setExplosionDamage(explosionDamage: Float) {
        this.explosionDamageValue = explosionDamage
    }

    override fun setExplosionRadius(radius: Float) {
        this.explosionRadiusValue = radius
    }

    override fun setLife(life: Int) {
        this.lifeValue = life
    }

    open fun getLife(): Int {
        return lifeValue
    }

    public override fun getGravity(): Float {
        return this.gravityValue
    }

    override fun setGravity(gravity: Float) {
        this.gravityValue = gravity
    }

    open fun largeTrail() {
        emitNativeTrail(ProjectileTrailKind.LARGE) { level, pos ->
            level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0)
        }
    }

    open fun mediumTrail() {
        emitNativeTrail(ProjectileTrailKind.MEDIUM) { level, pos ->
            val random = this.random.nextFloat()
            level.addParticle(
                CustomCloudOption(
                    0.6f,
                    0.58f,
                    0.57f,
                    (120 + 40 * random).toInt(),
                    1.5f + 0.5f * random,
                    0f,
                    cooldown = false,
                    light = false
                ), pos.x + 0.25f * random, pos.y + 0.25f * random, pos.z + 0.25f * random, 0.0, 0.0, 0.0
            )
        }
    }

    open fun smallTrail() {
        emitNativeTrail(ProjectileTrailKind.SMALL) { level, pos ->
            val random = this.random.nextFloat()
            level.addParticle(
                ParticleTypes.SMOKE,
                pos.x + 0.25f * random,
                pos.y + 0.25f * random,
                pos.z + 0.25f * random,
                0.0,
                0.0,
                0.0
            )
        }
    }

    private inline fun emitNativeTrail(kind: ProjectileTrailKind, emitParticle: (Level, Vec3) -> Unit) {
        val level = level()
        if (!level.isClientSide || ProjectileTrailProviders.emit(this, kind) || tickCount <= 2) return

        val current = Vec3(x, y + bbHeight / 2, z)
        val currentToPrevious = Vec3(xo, yo + bbHeight / 2, zo).subtract(current)
        val segmentLength = currentToPrevious.length()
        if (segmentLength <= 1.0e-6) return

        val direction = currentToPrevious.scale(1.0 / segmentLength)
        var distance = 0.0
        while (distance < segmentLength) {
            emitParticle(level, current.add(direction.scale(distance)))
            distance += NATIVE_TRAIL_SAMPLE_SPACING
        }
    }

    fun checkNoClip(target: Entity, pos: Vec3): Boolean {
        return this.level().clip(
            ClipContext(
                pos, target.boundingBox.center,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, this
            )
        ).type != HitResult.Type.BLOCK
    }

    override fun shoot(pX: Double, pY: Double, pZ: Double, pVelocity: Float, pInaccuracy: Float) {
        val vec3 = (Vec3(pX, pY, pZ)).normalize().add(
            this.random.triangle(0.0, 0.0172275 * pInaccuracy.toDouble()),
            this.random.triangle(0.0, 0.0172275 * pInaccuracy.toDouble()),
            this.random.triangle(0.0, 0.0172275 * pInaccuracy.toDouble())
        ).scale(pVelocity.toDouble())
        this.deltaMovement = vec3
        val d0 = vec3.horizontalDistance()
        this.yRot = (-Mth.atan2(vec3.x, vec3.z) * (180f / Math.PI.toFloat()).toDouble()).toFloat()
        this.xRot = (-Mth.atan2(vec3.y, d0) * (180f / Math.PI.toFloat()).toDouble()).toFloat()
        this.yRotO = this.yRot
        this.xRotO = this.xRot
    }

    companion object {
        private const val NATIVE_TRAIL_SAMPLE_SPACING = 2.0

        var playFlySound: Consumer<FastThrowableProjectile> = Consumer { }
        var playNearFlySound: Consumer<FastThrowableProjectile> = Consumer { }
    }
}
