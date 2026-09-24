package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionPhase
import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionProfile
import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionState
import com.atsuishio.superbwarfare.api.projectile.GuidedMissileGuidance
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidanceContext
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

open class WireGuideMissileEntity(type: EntityType<out WireGuideMissileEntity>, level: Level) :
    MissileProjectile(type, level), BasicGeoProjectileEntity {

    var launcherVehicleUUID: UUID? = null
    private var launcherWeaponGuidanceContext: VehicleWeaponGuidanceContext? = null
    fun launcherGuidanceContext(): VehicleWeaponGuidanceContext? = launcherWeaponGuidanceContext

    /** One flight path for all wire-guided missiles; native launchers use the explicit default. */
    private var propulsionState: GuidedPropulsionState? = null
    private var propulsionInitialized = false
    private var inheritedPlatformMotion = Vec3.ZERO
    private var guidedPropulsionEnabled = false
    private var latchedTopAttack: Boolean? = null

    private fun usesLatchedTopAttack(): Boolean = latchedTopAttack ?: runCatching {
        ProjectileProfiles.resolve(this)?.extension(ResourceLocation("superbwarfare", "guided_target_v1"))
            ?.asJsonObject?.get("Mode")?.asString == "TOP_ATTACK"
    }.getOrDefault(false).also { latchedTopAttack = it }

    init {
        this.noCulling = true
    }

    override fun defineSynchedData() {
        super.defineSynchedData()
        entityData.define(GUIDED_PROPULSION_ENABLED, false)
        entityData.define(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.FUEL_OUT.code)
        entityData.define(GUIDED_PROPULSION_SPEED, 0f)
        entityData.define(GUIDED_PROPULSION_INHERITED_X, 0f)
        entityData.define(GUIDED_PROPULSION_INHERITED_Y, 0f)
        entityData.define(GUIDED_PROPULSION_INHERITED_Z, 0f)
        entityData.define(GUIDED_PROPULSION_TRAIL_ACTIVE, false)
    }

    /** True only after a valid typed guided profile was initialized at launch/load time. */
    fun hasGuidedPropulsion(): Boolean =
        guidedPropulsionEnabled || entityData.get(GUIDED_PROPULSION_ENABLED)

    fun guidedPropulsionPhase(): GuidedPropulsionPhase =
        GuidedPropulsionPhase.fromCode(entityData.get(GUIDED_PROPULSION_PHASE))

    /** Presentation providers use this to suppress thrust/flame after fuel is exhausted. */
    fun isGuidedPropulsionThrusting(): Boolean =
        hasGuidedPropulsion() && guidedPropulsionPhase() == GuidedPropulsionPhase.THRUST

    /** The sixth ejection segment remains unpowered even when its endpoint reaches ignition. */
    fun suppressesGuidedPropulsionTrail(): Boolean = hasGuidedPropulsion() &&
        (guidedPropulsionPhase() == GuidedPropulsionPhase.EJECTION ||
            (isGuidedPropulsionThrusting() && !entityData.get(GUIDED_PROPULSION_TRAIL_ACTIVE)))

    fun guidedPropulsionSpeed(): Double {
        val synchronized = entityData.get(GUIDED_PROPULSION_SPEED).toDouble()
        return if (level().isClientSide && synchronized.isFinite() && synchronized > 0.0) {
            synchronized
        } else {
            propulsionState?.speed ?: 0.0
        }
    }

    /**
     * Captures one launch-time inherited vector and initializes the missile-relative propulsion
     * state.  The factory has already assembled the real launch vector; this method only records
     * the split so subsequent acceleration cannot clamp or duplicate platform motion.
     */
    fun initializeGuidedPropulsion(
        profile: GuidedPropulsionProfile,
        initialRelativeMotion: Vec3,
        inheritedMotion: Vec3,
    ): Boolean {
        if (!profile.isValid() || !finite(initialRelativeMotion) || !finite(inheritedMotion)) {
            disableGuidedPropulsion()
            return false
        }
        propulsionInitialized = true
        propulsionState = GuidedPropulsionState.launch(profile)
        inheritedPlatformMotion = Vec3(inheritedMotion.x, inheritedMotion.y, inheritedMotion.z)
        guidedPropulsionEnabled = true
        entityData.set(GUIDED_PROPULSION_ENABLED, true)
        entityData.set(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.EJECTION.code)
        entityData.set(GUIDED_PROPULSION_SPEED, profile.launchSpeed().toFloat())
        entityData.set(GUIDED_PROPULSION_TRAIL_ACTIVE, false)
        entityData.set(GUIDED_PROPULSION_INHERITED_X, inheritedMotion.x.toFloat())
        entityData.set(GUIDED_PROPULSION_INHERITED_Y, inheritedMotion.y.toFloat())
        entityData.set(GUIDED_PROPULSION_INHERITED_Z, inheritedMotion.z.toFloat())
        val direction = initialRelativeMotion.takeIf { it.lengthSqr() > 1.0e-12 }?.normalize()
            ?: lookAngle.takeIf { finite(it) && it.lengthSqr() > 1.0e-12 }?.normalize()
            ?: Vec3(0.0, 0.0, 1.0)
        deltaMovement = direction.scale(profile.launchSpeed()).add(inheritedMotion)
        return true
    }

    private fun disableGuidedPropulsion() {
        propulsionInitialized = true
        propulsionState = null
        inheritedPlatformMotion = Vec3.ZERO
        guidedPropulsionEnabled = false
        entityData.set(GUIDED_PROPULSION_ENABLED, false)
        entityData.set(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.FUEL_OUT.code)
        entityData.set(GUIDED_PROPULSION_SPEED, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_X, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_Y, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_Z, 0f)
        entityData.set(GUIDED_PROPULSION_TRAIL_ACTIVE, false)
    }

    override fun getDefaultItem(): Item {
        return ModItems.MEDIUM_ANTI_GROUND_MISSILE.get()
    }

    /** Exact target actually receiving guidance this tick; never inferred from nearby aircraft. */
    var actualGuidanceTargetUUID: UUID? = null
        private set
    private var previousFlightWobble = Vec3.ZERO

    override fun tick() {
        actualGuidanceTargetUUID = null
        val maneuver = com.atsuishio.superbwarfare.api.projectile.GuidedManeuverPolicy.from(ProjectileProfiles.resolve(this))
        val guided = propulsionState?.profile ?: ProjectileProfiles.guidedPropulsion(this)
            ?: GuidedPropulsionProfile.DEFAULT
        if (!level().isClientSide && !propulsionInitialized) {
            initializeGuidedPropulsion(guided, deltaMovement, Vec3.ZERO)
        }
        val movementPhase = guidedPropulsionPhase()
        super.tick()
        if (isRemoved) return
        if (!finite(deltaMovement)) {
            discard()
            return
        }

        val guidedInheritedMotion = effectiveInheritedMotion()
        if (!level().isClientSide) {
            // super.tick moved and collided along the actual wobbled flight segment. Guidance
            // starts from the underlying course, so oscillation cannot accumulate into a turn.
            deltaMovement = GuidedMissileGuidance.removeSpinPerturbation(deltaMovement, guidedInheritedMotion, previousFlightWobble)
            previousFlightWobble = Vec3.ZERO
            propulsionState?.let { state ->
                val relativeSpeed = deltaMovement.subtract(guidedInheritedMotion).length()
                if (!relativeSpeed.isFinite()) {
                    discard()
                    return
                }
                state.completeMovement(relativeSpeed)?.let(::applyGuidedPropulsionMagnitude)
                entityData.set(GUIDED_PROPULSION_PHASE, state.phase.code)
                entityData.set(GUIDED_PROPULSION_SPEED, state.speed.toFloat())
                entityData.set(GUIDED_PROPULSION_TRAIL_ACTIVE,
                    movementPhase == GuidedPropulsionPhase.THRUST &&
                        state.phase == GuidedPropulsionPhase.THRUST)
            }
        } else if (isGuidedPropulsionThrusting()) {
            applyGuidedPropulsionMagnitude(guidedPropulsionSpeed())
        }
        mediumTrail()

        val owner = this.owner
        val vehicle = owner?.vehicle
        val laserPointMode = persistentData.hasUUID("BvpLaserAircraft")
        if (!level().isClientSide && hasGuidedPropulsion() && movementPhase != GuidedPropulsionPhase.EJECTION &&
            tickCount > 0 && (laserPointMode || usesLatchedTopAttack() || owner != null && vehicle is VehicleEntity)) {
            var toVec = deltaMovement.subtract(guidedInheritedMotion)
            val relativeSpeed = toVec.length()

            val topAttackTarget = if (!laserPointMode && usesLatchedTopAttack()) {
                val tracked = if (targetUUID != "none") EntityFindUtil.findEntity(level(), targetUUID) else null
                if (tracked != null && tracked.isAlive) {
                    tracked.boundingBox.center.takeIf(::finite)?.let { targetPos = it }
                }
                targetPos?.takeIf(::finite)
            } else null
            if (persistentData.getString("BvpCommandMode") == "MCLOS") {
                val context = launcherWeaponGuidanceContext
                if (vehicle is VehicleEntity && owner is net.minecraft.server.level.ServerPlayer &&
                    launcherVehicleUUID == vehicle.uuid && context?.launcherControllerUUID == owner.uuid &&
                    vehicle.getSeatIndex(owner) == context.seatIndex &&
                    vehicle.getGunName(context.seatIndex,context.weaponIndex) == context.weaponName) {
                    val up = Vec3(persistentData.getDouble("BvpCommandUpX"),
                        persistentData.getDouble("BvpCommandUpY"),persistentData.getDouble("BvpCommandUpZ"))
                    toVec = com.atsuishio.superbwarfare.api.aircraft.AircraftManualCommand.direction(
                        vehicle,owner,toVec,up,guided.maxTurnRateDegreesPerSecond)
                }
            } else if (laserPointMode) {
                // Aircraft-owned point guidance remains valid without a mounted or loaded carrier.
                // Explicit clear coasts; it must never fall through to manual ATGM camera guidance.
                val point = com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.laserTarget(this)
                val desired = GuidedMissileGuidance.laserSeekerDirection(position(), lookAngle, point,
                    relativeSpeed, guidedInheritedMotion)
                if (desired != null) {
                    // Out-of-cone or cleared points coast without a steering command or RWR target.
                    // A visible designation can be acquired again; body G/turn limits still apply.
                    toVec = desired
                    // A point genuinely painted on a vehicle can trigger its receiver; nearby traffic cannot.
                    if (point != null) actualGuidanceTargetUUID = level().getEntitiesOfClass(VehicleEntity::class.java,
                        net.minecraft.world.phys.AABB(point, point).inflate(0.001)) {
                        it.isAlive && !it.isWreck && it.boundingBox.contains(point)
                    }.minByOrNull { it.id }?.uuid
                }
            } else if (topAttackTarget != null) {
                GuidedMissileGuidance.topAttackDirection(position(), topAttackTarget)?.let {
                    toVec = it
                    if (!lost && !lostTarget && !distracted) actualGuidanceTargetUUID = runCatching { UUID.fromString(targetUUID) }.getOrNull()
                }
            } else if (vehicle is VehicleEntity && launcherVehicleUUID == vehicle.uuid) {
                val launchContext = launcherWeaponGuidanceContext
                val guidanceRay = if (launchContext != null) {
                    (owner as? LivingEntity)?.let {
                        vehicle.resolveLaunchedWeaponGuidanceRay(it, launchContext)
                    }
                } else null
                if (guidanceRay != null) {
                    GuidedMissileGuidance.targetOnRay(position(), guidanceRay.origin, guidanceRay.direction,
                        relativeSpeed, guided.guidanceLookAheadTicks)?.let {
                        toVec = it
                    }
                }
                // Missing/stale ownership or camera input means coast, never barrel steering.
            }

            deltaMovement = if (maneuver == null)
                GuidedMissileGuidance.steer(deltaMovement, guidedInheritedMotion, toVec, guided.maxTurnRateDegreesPerSecond)
            else GuidedMissileGuidance.steerManeuver(deltaMovement, guidedInheritedMotion, toVec,
                guided.maxTurnRateDegreesPerSecond, maneuver)
            val relative = deltaMovement.subtract(guidedInheritedMotion)
            if (relative.lengthSqr() > 1.0e-12) {
                yRot = Math.toDegrees(kotlin.math.atan2(-relative.x, relative.z)).toFloat()
                xRot = Math.toDegrees(kotlin.math.atan2(-relative.y, relative.horizontalDistance())).toFloat()
            }
        }
        if (!level().isClientSide && hasGuidedPropulsion() && movementPhase != GuidedPropulsionPhase.EJECTION && maneuver == null) {
            val clean = deltaMovement
            deltaMovement = GuidedMissileGuidance.spinPerturbation(clean, guidedInheritedMotion, tickCount, guided.maxSpeed)
            previousFlightWobble = deltaMovement.subtract(clean)
            val relative = deltaMovement.subtract(guidedInheritedMotion)
            yRot = Math.toDegrees(kotlin.math.atan2(-relative.x, relative.z)).toFloat()
            xRot = Math.toDegrees(kotlin.math.atan2(-relative.y, relative.horizontalDistance())).toFloat()
        }
        commitTickSynchronization()
    }

    override fun deferTickSynchronization(): Boolean = true

    override fun isNoGravity(): Boolean = guidedPropulsionPhase() != GuidedPropulsionPhase.EJECTION

    override fun getGravity(): Float = if (guidedPropulsionPhase() == GuidedPropulsionPhase.EJECTION)
        (propulsionState?.profile ?: ProjectileProfiles.guidedPropulsion(this)
            ?: GuidedPropulsionProfile.DEFAULT).ejectionGravityPerTick.toFloat() else 0f

    /** Applies the current missile-relative magnitude and adds the frozen platform vector once. */
    private fun applyGuidedPropulsionMagnitude(speed: Double) {
        if (!speed.isFinite() || speed <= 0.0) return
        val inherited = effectiveInheritedMotion()
        val relative = deltaMovement.subtract(inherited)
        val relativeLengthSquared = relative.lengthSqr()
        val fallback = lookAngle
        val direction = when {
            relativeLengthSquared.isFinite() && relativeLengthSquared > 1.0e-10 -> relative.normalize()
            finite(fallback) && fallback.lengthSqr() > 1.0e-10 -> fallback.normalize()
            else -> Vec3(0.0, 0.0, 1.0)
        }
        deltaMovement = direction.scale(speed).add(inherited)
    }

    private fun effectiveInheritedMotion(): Vec3 {
        if (!level().isClientSide) return inheritedPlatformMotion
        val synchronized = Vec3(
            entityData.get(GUIDED_PROPULSION_INHERITED_X).toDouble(),
            entityData.get(GUIDED_PROPULSION_INHERITED_Y).toDouble(),
            entityData.get(GUIDED_PROPULSION_INHERITED_Z).toDouble(),
        )
        return if (finite(synchronized)) synchronized else Vec3.ZERO
    }

    override fun getSound(): SoundEvent {
        return ModSounds.ROCKET_FLY.get()
    }

    override fun getVolume(): Float {
        return 0.4f
    }

    fun setLauncherVehicle(uuid: UUID?) {
        this.launcherVehicleUUID = uuid
    }

    fun setLauncherWeaponGuidanceContext(context: VehicleWeaponGuidanceContext?) {
        this.launcherWeaponGuidanceContext = context
    }

    override fun addAdditionalSaveData(compound: CompoundTag) {
        super.addAdditionalSaveData(compound)
        targetPos?.takeIf(::finite)?.let {
            compound.putDouble("LatchedTargetX", it.x)
            compound.putDouble("LatchedTargetY", it.y)
            compound.putDouble("LatchedTargetZ", it.z)
        }
        if (guidedPropulsionEnabled && finite(inheritedPlatformMotion)) {
            propulsionState?.writeTo(compound)
            compound.putDouble("GuidedPropulsionInheritedX", inheritedPlatformMotion.x)
            compound.putDouble("GuidedPropulsionInheritedY", inheritedPlatformMotion.y)
            compound.putDouble("GuidedPropulsionInheritedZ", inheritedPlatformMotion.z)
        }
        launcherVehicleUUID?.let { compound.putUUID("LauncherVehicleUUID", it) }
        launcherWeaponGuidanceContext?.let { context ->
            compound.putUUID("LauncherContextVehicleUUID", context.launcherVehicleUUID)
            context.launcherControllerUUID?.let { compound.putUUID("LauncherControllerUUID", it) }
            compound.putInt("LauncherSeatIndex", context.seatIndex)
            compound.putInt("LauncherWeaponIndex", context.weaponIndex)
            compound.putString("LauncherWeaponName", context.weaponName)
            compound.putBoolean("LauncherHelicopterAtgm", context.helicopterAtgm)
            context.roundId?.let { compound.putString("LauncherRoundId", it.toString()) }
        }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        super.readAdditionalSaveData(compound)
        latchedTopAttack = null
        targetPos = if (listOf("LatchedTargetX", "LatchedTargetY", "LatchedTargetZ")
                .all { compound.contains(it, Tag.TAG_DOUBLE.toInt()) }) {
            Vec3(compound.getDouble("LatchedTargetX"), compound.getDouble("LatchedTargetY"),
                compound.getDouble("LatchedTargetZ")).takeIf(::finite)
        } else null
        disableGuidedPropulsion()
        if (compound.getBoolean("GuidedPropulsionEnabled") &&
            compound.contains("GuidedPropulsionPhase", Tag.TAG_BYTE.toInt()) &&
            compound.contains("GuidedPropulsionSpeed", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionElapsed", Tag.TAG_INT.toInt()) &&
            compound.contains("GuidedPropulsionInheritedX", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionInheritedY", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionInheritedZ", Tag.TAG_DOUBLE.toInt())
        ) {
            val state = GuidedPropulsionState.restore(compound,
                ProjectileProfiles.guidedPropulsion(this) ?: GuidedPropulsionProfile.DEFAULT)
            val inherited = Vec3(
                compound.getDouble("GuidedPropulsionInheritedX"),
                compound.getDouble("GuidedPropulsionInheritedY"),
                compound.getDouble("GuidedPropulsionInheritedZ"),
            )
            if (state != null && finite(inherited) && finite(deltaMovement)) {
                propulsionState = state
                inheritedPlatformMotion = inherited
                guidedPropulsionEnabled = true
                entityData.set(GUIDED_PROPULSION_ENABLED, true)
                entityData.set(GUIDED_PROPULSION_PHASE, state.phase.code)
                entityData.set(GUIDED_PROPULSION_SPEED, state.speed.toFloat())
                entityData.set(GUIDED_PROPULSION_TRAIL_ACTIVE,
                    state.phase == GuidedPropulsionPhase.THRUST && state.motorTicks > 0)
                entityData.set(GUIDED_PROPULSION_INHERITED_X, inherited.x.toFloat())
                entityData.set(GUIDED_PROPULSION_INHERITED_Y, inherited.y.toFloat())
                entityData.set(GUIDED_PROPULSION_INHERITED_Z, inherited.z.toFloat())
            } else {
                disableGuidedPropulsion()
            }
        }
        launcherVehicleUUID = if (compound.hasUUID("LauncherVehicleUUID")) {
            compound.getUUID("LauncherVehicleUUID")
        } else {
            null
        }
        launcherWeaponGuidanceContext = if (compound.contains("LauncherSeatIndex") &&
            compound.contains("LauncherWeaponIndex") &&
            compound.contains("LauncherWeaponName") &&
            compound.contains("LauncherHelicopterAtgm")
        ) {
            runCatching {
                val contextVehicleUuid = if (compound.hasUUID("LauncherContextVehicleUUID")) {
                    compound.getUUID("LauncherContextVehicleUUID")
                } else {
                    launcherVehicleUUID ?: return@runCatching null
                }
                val contextControllerUuid = if (compound.hasUUID("LauncherControllerUUID")) {
                    compound.getUUID("LauncherControllerUUID")
                } else {
                    null
                }
                val helicopterAtgm = compound.getBoolean("LauncherHelicopterAtgm")
                val roundId = compound.getString("LauncherRoundId")
                    .takeIf { it.isNotBlank() && it.length <= 128 }
                    ?.let { ResourceLocation.tryParse(it) }
                if (helicopterAtgm && roundId == null) return@runCatching null
                VehicleWeaponGuidanceContext(
                    contextVehicleUuid,
                    contextControllerUuid,
                    compound.getInt("LauncherSeatIndex"),
                    compound.getInt("LauncherWeaponIndex"),
                    compound.getString("LauncherWeaponName").takeIf { it.length <= 128 } ?: return@runCatching null,
                    helicopterAtgm,
                    roundId,
                )
            }.getOrNull()
        } else {
            null
        }
    }

    override val maxHealth: Float
        get() = 20f

    override fun getModel() = BedrockModelLoader.WIRE_GUIDE_MISSILE_MODEL

    companion object {
        @JvmField
        val GUIDED_PROPULSION_ENABLED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val GUIDED_PROPULSION_PHASE: EntityDataAccessor<Byte> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.BYTE)

        @JvmField
        val GUIDED_PROPULSION_SPEED: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GUIDED_PROPULSION_INHERITED_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GUIDED_PROPULSION_INHERITED_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GUIDED_PROPULSION_INHERITED_Z: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GUIDED_PROPULSION_TRAIL_ACTIVE: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(WireGuideMissileEntity::class.java, EntityDataSerializers.BOOLEAN)

        /**
         * Projectile-to-projectile interception is deliberately narrower than the legacy
         * swept/AABB query.  The radius is derived from the missile's physical hitbox, with a
         * small floor for finite registered dimensions and a cap that keeps the interception
         * volume substantially below the old 0.5-block query inflation.
         */
        const val MIN_INTERCEPTION_RADIUS = 0.05
        const val INTERCEPTION_RADIUS_FRACTION = 0.25
        const val MAX_INTERCEPTION_RADIUS = 0.20
        @JvmStatic
        fun interceptionRadius(missile: WireGuideMissileEntity): Double {
            val diameter = min(missile.bbWidth.toDouble(), missile.bbHeight.toDouble())
            if (!diameter.isFinite() || diameter <= 0.0) return Double.NaN
            return max(
                MIN_INTERCEPTION_RADIUS,
                min(MAX_INTERCEPTION_RADIUS, diameter * INTERCEPTION_RADIUS_FRACTION)
            )
        }

        /**
         * Returns the first point where the centerline segment enters the reduced interception
         * sphere, or null for a miss/invalid state.  The segment sweep remains continuous, so a
         * high-speed bullet can intercept a missile between ticks without restoring broad AABB
         * inflation.  This helper has no side effects and is shared by all projectile paths.
         */
        @JvmStatic
        fun preciseInterceptionHitPoint(
            missile: WireGuideMissileEntity,
            start: Vec3,
            end: Vec3,
        ): Vec3? {
            if (missile.isRemoved || !finite(start) || !finite(end)) return null
            val radius = interceptionRadius(missile)
            if (!radius.isFinite() || radius <= 0.0) return null

            val box = missile.boundingBox
            val center = Vec3(
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5,
            )
            if (!finite(center)) return null

            val dx = end.x - start.x
            val dy = end.y - start.y
            val dz = end.z - start.z
            val a = dx * dx + dy * dy + dz * dz
            val radiusSquared = radius * radius
            if (!a.isFinite() || !radiusSquared.isFinite()) return null

            if (a <= 1.0e-12) {
                return if (start.distanceToSqr(center) <= radiusSquared) start else null
            }

            val fx = start.x - center.x
            val fy = start.y - center.y
            val fz = start.z - center.z
            val b = 2.0 * (fx * dx + fy * dy + fz * dz)
            val c = fx * fx + fy * fy + fz * fz - radiusSquared
            val startDistanceSquared = fx * fx + fy * fy + fz * fz
            if (!startDistanceSquared.isFinite()) return null
            if (startDistanceSquared <= radiusSquared) return start
            val discriminant = b * b - 4.0 * a * c
            if (!b.isFinite() || !c.isFinite() || !discriminant.isFinite() || discriminant < 0.0) {
                return null
            }

            val root = sqrt(discriminant.coerceAtLeast(0.0))
            val denominator = 2.0 * a
            var t = (-b - root) / denominator
            if (t < 0.0) t = (-b + root) / denominator
            if (!t.isFinite() || t < -1.0e-9 || t > 1.0 + 1.0e-9) return null
            t = t.coerceIn(0.0, 1.0)

            return Vec3(start.x + dx * t, start.y + dy * t, start.z + dz * t)
        }

        @JvmStatic
        fun preciseInterceptionHit(
            missile: WireGuideMissileEntity,
            start: Vec3,
            end: Vec3,
        ): Boolean = preciseInterceptionHitPoint(missile, start, end) != null

        private fun finite(vector: Vec3): Boolean =
            vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite() && vector.lengthSqr().isFinite()
    }
}
