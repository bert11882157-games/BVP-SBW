package com.atsuishio.superbwarfare.entity.projectile

import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionPhase
import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionProfile
import com.atsuishio.superbwarfare.api.projectile.GuidedMissileGuidance
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidanceContext
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.resource.BedrockModelLoader
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

    /** One flight path for all wire-guided missiles; native launchers use the explicit default. */
    private var guidedPropulsionProfile: GuidedPropulsionProfile? = null
    private var guidedPropulsionSpeed = 0.0
    private var guidedPropulsionElapsedTicks = 0
    private var inheritedPlatformMotion = Vec3.ZERO
    private var guidedPropulsionEnabled = false

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
    }

    /** True only after a valid typed guided profile was initialized at launch/load time. */
    fun hasGuidedPropulsion(): Boolean =
        guidedPropulsionEnabled || entityData.get(GUIDED_PROPULSION_ENABLED)

    fun guidedPropulsionPhase(): GuidedPropulsionPhase =
        GuidedPropulsionPhase.fromCode(entityData.get(GUIDED_PROPULSION_PHASE))

    /** Presentation providers use this to suppress thrust/flame after fuel is exhausted. */
    fun isGuidedPropulsionThrusting(): Boolean =
        hasGuidedPropulsion() && guidedPropulsionPhase() == GuidedPropulsionPhase.THRUST

    fun guidedPropulsionSpeed(): Double {
        val synchronized = entityData.get(GUIDED_PROPULSION_SPEED).toDouble()
        return if (level().isClientSide && synchronized.isFinite() && synchronized > 0.0) {
            synchronized
        } else {
            guidedPropulsionSpeed
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
        guidedPropulsionProfile = profile
        guidedPropulsionSpeed = profile.initialSpeed
        guidedPropulsionElapsedTicks = 0
        inheritedPlatformMotion = Vec3(inheritedMotion.x, inheritedMotion.y, inheritedMotion.z)
        guidedPropulsionEnabled = true
        entityData.set(GUIDED_PROPULSION_ENABLED, true)
        entityData.set(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.THRUST.code)
        entityData.set(GUIDED_PROPULSION_SPEED, profile.initialSpeed.toFloat())
        entityData.set(GUIDED_PROPULSION_INHERITED_X, inheritedMotion.x.toFloat())
        entityData.set(GUIDED_PROPULSION_INHERITED_Y, inheritedMotion.y.toFloat())
        entityData.set(GUIDED_PROPULSION_INHERITED_Z, inheritedMotion.z.toFloat())
        val direction = initialRelativeMotion.takeIf { it.lengthSqr() > 1.0e-12 }?.normalize()
            ?: lookAngle.takeIf { finite(it) && it.lengthSqr() > 1.0e-12 }?.normalize()
            ?: Vec3(0.0, 0.0, 1.0)
        deltaMovement = direction.scale(profile.initialSpeed).add(inheritedMotion)
        return true
    }

    private fun disableGuidedPropulsion() {
        guidedPropulsionProfile = null
        guidedPropulsionSpeed = 0.0
        guidedPropulsionElapsedTicks = 0
        inheritedPlatformMotion = Vec3.ZERO
        guidedPropulsionEnabled = false
        entityData.set(GUIDED_PROPULSION_ENABLED, false)
        entityData.set(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.FUEL_OUT.code)
        entityData.set(GUIDED_PROPULSION_SPEED, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_X, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_Y, 0f)
        entityData.set(GUIDED_PROPULSION_INHERITED_Z, 0f)
    }

    override fun getDefaultItem(): Item {
        return ModItems.MEDIUM_ANTI_GROUND_MISSILE.get()
    }

    override fun tick() {
        val guided = guidedPropulsionProfile ?: ProjectileProfiles.guidedPropulsion(this)
            ?: GuidedPropulsionProfile.DEFAULT
        if (!level().isClientSide && !hasGuidedPropulsion()) {
            initializeGuidedPropulsion(guided, deltaMovement, Vec3.ZERO)
        }
        super.tick()

        // Propulsion is launch-owned and must continue even if the launcher dismounts or dies;
        // only guidance below depends on the current mounted controller.  The server advances
        // the phase once, while clients consume the synchronized speed/phase for presentation.
        val guidedInheritedMotion = effectiveInheritedMotion()
        var transitionAfterStep = false
        if (hasGuidedPropulsion()) {
            if (!level().isClientSide) transitionAfterStep = advanceGuidedPropulsion(guided)
            // Keep the terminal acceleration step in the THRUST phase.  The phase flips only
            // after its new relative speed has been applied, so FUEL_OUT never erases that last
            // propulsion update while still forbidding all later acceleration.
            applyGuidedPropulsionMagnitude(guided)
            if (transitionAfterStep) transitionToFuelOut()
        }
        // Evaluate the phase after the authoritative boundary transition so a FUEL_OUT missile
        // cannot emit one extra thrust/flame sample.  The provider falls through to the normal
        // smoke-only trail once this call observes FUEL_OUT.
        mediumTrail()

        val owner = this.owner
        val vehicle = owner?.vehicle
        if (!level().isClientSide && tickCount > 0 && owner != null && vehicle is VehicleEntity) {
            var toVec = deltaMovement.subtract(guidedInheritedMotion)
            val relativeSpeed = toVec.length()

            if (launcherVehicleUUID == vehicle.uuid) {
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

            deltaMovement = GuidedMissileGuidance.steer(deltaMovement, guidedInheritedMotion, toVec,
                guided.maxTurnRateDegreesPerSecond)
            val relative = deltaMovement.subtract(guidedInheritedMotion)
            if (relative.lengthSqr() > 1.0e-12) {
                yRot = Math.toDegrees(kotlin.math.atan2(-relative.x, relative.z)).toFloat()
                xRot = Math.toDegrees(kotlin.math.atan2(-relative.y, relative.horizontalDistance())).toFloat()
            }
        }
    }

    /** Advances relative propulsion once on the authoritative server, then publishes phase/speed. */
    private fun advanceGuidedPropulsion(profile: GuidedPropulsionProfile): Boolean {
        if (!guidedPropulsionEnabled || guidedPropulsionPhase() != GuidedPropulsionPhase.THRUST) return false
        if (guidedPropulsionElapsedTicks >= profile.thrustDurationTicks) {
            transitionToFuelOut()
            return false
        }

        guidedPropulsionSpeed = profile.speedAfter(guidedPropulsionElapsedTicks + 1)
            .coerceIn(profile.initialSpeed, profile.maxSpeed)
        guidedPropulsionElapsedTicks++
        entityData.set(GUIDED_PROPULSION_SPEED, guidedPropulsionSpeed.toFloat())
        // The caller applies this final speed while phase is still THRUST, then performs the
        // one-way transition before trail presentation and the next tick's coast step.
        return guidedPropulsionElapsedTicks >= profile.thrustDurationTicks
    }

    private fun transitionToFuelOut() {
        if (entityData.get(GUIDED_PROPULSION_PHASE) == GuidedPropulsionPhase.FUEL_OUT.code) return
        entityData.set(GUIDED_PROPULSION_PHASE, GuidedPropulsionPhase.FUEL_OUT.code)
    }

    /** Applies the current missile-relative magnitude and adds the frozen platform vector once. */
    private fun applyGuidedPropulsionMagnitude(profile: GuidedPropulsionProfile) {
        // FUEL_OUT is a one-way terminal propulsion phase: keep only the normal throwable drag
        // coast performed by super.tick(), with no further acceleration or magnitude reset.
        if (guidedPropulsionPhase() != GuidedPropulsionPhase.THRUST) return
        val speed = guidedPropulsionSpeed()
            .takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(profile.initialSpeed, profile.maxSpeed)
            ?: return
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
        if (guidedPropulsionEnabled && guidedPropulsionProfile?.isValid() == true &&
            guidedPropulsionSpeed.isFinite() && guidedPropulsionElapsedTicks in 0..GuidedPropulsionProfile.MAX_THRUST_DURATION_TICKS &&
            finite(inheritedPlatformMotion)
        ) {
            compound.putBoolean("GuidedPropulsionEnabled", true)
            compound.putByte("GuidedPropulsionPhase", guidedPropulsionPhase().code)
            compound.putDouble("GuidedPropulsionSpeed", guidedPropulsionSpeed)
            compound.putInt("GuidedPropulsionElapsed", guidedPropulsionElapsedTicks)
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
        if (compound.getBoolean("GuidedPropulsionEnabled") &&
            compound.contains("GuidedPropulsionPhase", Tag.TAG_BYTE.toInt()) &&
            compound.contains("GuidedPropulsionSpeed", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionElapsed", Tag.TAG_INT.toInt()) &&
            compound.contains("GuidedPropulsionInheritedX", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionInheritedY", Tag.TAG_DOUBLE.toInt()) &&
            compound.contains("GuidedPropulsionInheritedZ", Tag.TAG_DOUBLE.toInt())
        ) {
            val profile = ProjectileProfiles.guidedPropulsion(this) ?: GuidedPropulsionProfile.DEFAULT
            val phase = GuidedPropulsionPhase.fromCode(compound.getByte("GuidedPropulsionPhase"))
            val speed = compound.getDouble("GuidedPropulsionSpeed")
            val elapsed = compound.getInt("GuidedPropulsionElapsed")
            val inherited = Vec3(
                compound.getDouble("GuidedPropulsionInheritedX"),
                compound.getDouble("GuidedPropulsionInheritedY"),
                compound.getDouble("GuidedPropulsionInheritedZ"),
            )
            if (profile.isValid() && speed.isFinite() &&
                speed in profile.initialSpeed..profile.maxSpeed &&
                elapsed in 0..profile.thrustDurationTicks && finite(inherited)
            ) {
                guidedPropulsionProfile = profile
                guidedPropulsionSpeed = speed
                guidedPropulsionElapsedTicks = elapsed
                inheritedPlatformMotion = inherited
                guidedPropulsionEnabled = true
                entityData.set(GUIDED_PROPULSION_ENABLED, true)
                entityData.set(GUIDED_PROPULSION_PHASE, phase.code)
                entityData.set(GUIDED_PROPULSION_SPEED, speed.toFloat())
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
            vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
    }
}
