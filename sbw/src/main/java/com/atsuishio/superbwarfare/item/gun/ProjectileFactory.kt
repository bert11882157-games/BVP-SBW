package com.atsuishio.superbwarfare.item.gun

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.api.projectile.GuidedPropulsionProfile
import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponShotDiagnostics
import com.atsuishio.superbwarfare.data.CustomData
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.data.launchable.LaunchableEntityTool
import com.atsuishio.superbwarfare.data.launchable.ShootData
import com.atsuishio.superbwarfare.entity.projectile.*
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModEntities
import com.atsuishio.superbwarfare.perk.Perk
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.server.level.ServerLevel
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3

internal data class PreparedProjectile(val entity: Entity, val velocity: Float)

/** Creates and launches configured projectile entities without owning trigger or ammo state. */
object ProjectileFactory {
    private const val ENTITY_GUIDANCE = 0
    private const val POSITION_GUIDANCE = 1

    internal fun prepare(parameters: ShootParameters): PreparedProjectile? {
        val projectileData = parameters.projectileData ?: parameters.data
        val projectileInfo = projectileData.get(GunProp.PROJECTILE)
        val projectileType = projectileInfo.itemId.trim()
        var velocity = adjustedVelocity(parameters)
        val entityType = EntityType.byString(projectileType).orElse(null)
        if (entityType == null) {
            VehicleWeaponShotDiagnostics.recordProjectileBoundary(
                parameters,
                VehicleWeaponShotDiagnostics.Boundary.PROJECTILE_ENTITY,
                "TYPE_UNAVAILABLE",
            )
            Mod.LOGGER.warn("Failed to create projectile entity {}", projectileType)
            return null
        }
        val entity = entityType.create(parameters.level)
        if (entity == null) {
            VehicleWeaponShotDiagnostics.recordProjectileBoundary(
                parameters,
                VehicleWeaponShotDiagnostics.Boundary.PROJECTILE_ENTITY,
                "CREATE_FAILED",
            )
            Mod.LOGGER.warn("Failed to create projectile entity {}", projectileType)
            return null
        }

        configureCommon(entity, parameters, projectileData, velocity)
        configureTyped(entity, projectileData)
        configureGuidance(entity, parameters)
        configureLaunchableData(entity, parameters, projectileData, projectileInfo, projectileType)
        val requestedProfileId = projectileInfo.resolvedProfileId()
        ProjectileProfiles.assign(entity, requestedProfileId)
        if (requestedProfileId != null) {
            val assignedProfileId = ProjectileProfiles.profileId(entity)
            when {
                assignedProfileId == null -> VehicleWeaponShotDiagnostics.recordProjectileBoundary(
                    parameters,
                    VehicleWeaponShotDiagnostics.Boundary.PROJECTILE_PROFILE,
                    "ENTITY_UNSUPPORTED",
                )

                ProjectileProfiles.resolve(entity) == null -> VehicleWeaponShotDiagnostics.recordProjectileBoundary(
                    parameters,
                    VehicleWeaponShotDiagnostics.Boundary.PROJECTILE_PROFILE,
                    "PROFILE_UNAVAILABLE",
                )
            }
        }
        // Resolve the opt-in propulsion after creating the concrete entity.  BVP guided
        // projectiles (for example ataka_missile) are subclasses of WireGuideMissileEntity but
        // are registered by the addon, so selecting by the raw EntityType string here would
        // silently leave them on the legacy launch speed.  The actual typed entity/profile pair
        // is the stable seam and keeps all non-wire projectiles on the existing speed path.
        if (entity is WireGuideMissileEntity) {
            val flight = ProjectileProfiles.guidedPropulsion(entity) ?: GuidedPropulsionProfile.DEFAULT
            velocity = flight.initialSpeed.toFloat()
        }
        applyPerks(entity, projectileData)
        // PG-9/OG-9 remain non-tracer combat rounds, but their typed presentation contract
        // requests the standard small white tracer.  Belt policies remain the final per-shot
        // authority and can still suppress/replace this snapshot when explicitly authored.
        ProjectileProfiles.assignTypedSmallWhiteTracerPresentation(entity)
        // The selected belt round is the final presentation authority.  Apply it after every
        // generic/perk projectile mutation so an authored tracer_v2/default fallback cannot
        // restore a suppressed APDS trail or overwrite a forced green round.  Presentation
        // materialization is best-effort: a missing/temporarily unavailable client snapshot (or
        // a projectile type without the optional profile interface) must never reject an
        // otherwise valid server-authoritative shot.  Combat/profile selection remains owned by
        // projectileData above and the normal entity factory/spawn path is unchanged.
        runCatching {
            ProjectileProfiles.assignShotTracerPolicy(entity, parameters.projectileBeltTracer)
        }
        ProjectileProfiles.assignShotSequence(entity, currentProjectileShotSequence())
        return PreparedProjectile(entity, velocity)
    }

    internal fun spawn(prepared: PreparedProjectile, parameters: ShootParameters): Boolean {
        val entity = prepared.entity
        entity.setPos(parameters.shootPosition)
        // Capture the exact server muzzle point after setPos and before insertion.  Armor curve
        // sampling uses this immutable provenance plus the accepted collision point; it never
        // derives range from the shooter, camera, or a client interpolation state.
        ProjectileProfiles.recordServerLaunchPosition(entity, entity.position())
        applyTrajectory(entity, parameters.shootDirection, prepared.velocity, parameters.spread)

        val projectileData = parameters.projectileData ?: parameters.data
        var inheritedMotion = Vec3.ZERO
        if (projectileData.get(GunProp.ADD_SHOOTER_DELTA_MOVEMENT)) {
            parameters.shooter?.rootVehicle?.deltaMovement?.let {
                inheritedMotion = it
                entity.deltaMovement = entity.deltaMovement.add(it)
            }
        }
        // Guided propulsion is initialized after the live launch vector is assembled.  The
        // inherited platform vector remains a separate component and is never included in the
        // missile-relative speed cap.  Unguided and non-opted projectiles take the unchanged
        // path above.
        if (entity is WireGuideMissileEntity) {
            entity.initializeGuidedPropulsion(
                ProjectileProfiles.guidedPropulsion(entity) ?: GuidedPropulsionProfile.DEFAULT,
                entity.deltaMovement.subtract(inheritedMotion),
                inheritedMotion,
            )
        }
        val inserted = parameters.level.addFreshEntity(entity)
        if (!inserted) {
            VehicleWeaponShotDiagnostics.recordProjectileBoundary(
                parameters,
                VehicleWeaponShotDiagnostics.Boundary.INSERTION,
                "LEVEL_REJECTED",
            )
            ProjectileProfiles.clearServerLaunchPosition(entity)
        }
        return inserted
    }

    /**
     * Spawns one bounded impact fragment through the same SBW projectile entity/trajectory path
     * used by accepted fire. It has no GunData, ammo, RNG spread, explosion or scheduler state;
     * the caller supplies only the already-authoritative impact direction and presentation
     * template. A missing/invalid template fails closed before insertion.
     */
    @JvmStatic
    fun spawnImpactShrapnel(
        level: ServerLevel,
        owner: Entity?,
        position: Vec3,
        direction: Vec3,
        speed: Float,
        lifetimeTicks: Int,
        damage: Float,
        templateProfileId: ResourceLocation,
        presentationScale: Float,
        shotSequence: Long,
    ): Boolean {
        if (!finite(position) || !finite(direction) || direction.lengthSqr() <= 1.0E-8
            || !speed.isFinite() || speed <= 0f || lifetimeTicks !in 1..8
            || !damage.isFinite() || damage < 0f
            || !presentationScale.isFinite() || presentationScale <= 0f
        ) return false

        val entity = ModEntities.PROJECTILE.get().create(level) ?: return false
        if (!ProjectileProfiles.assignImpactTracerProfile(entity, templateProfileId, presentationScale)) {
            return false
        }
        val projectile = entity.shooter(owner)
            .damage(damage)
            .velocity(speed)
        projectile.setGravity(0.05f)
        projectile.setExplosionDamage(0f)
        projectile.setExplosionRadius(0f)
        projectile.setLife(lifetimeTicks)
        projectile.markImpactShrapnel()
        ProjectileProfiles.assignShotSequence(projectile, shotSequence)
        projectile.setPos(position)
        projectile.shoot(null, direction.x, direction.y, direction.z, speed, 0f)
        return level.addFreshEntity(projectile)
    }

    private fun finite(value: Vec3): Boolean =
        value.x.isFinite() && value.y.isFinite() && value.z.isFinite()

    private fun adjustedVelocity(parameters: ShootParameters): Float {
        // Guided initial speed is selected after entity/profile materialization in prepare().
        // Keep this helper as the exact legacy Float-rounded launch-speed calculation so
        // unguided and non-opted projectiles retain their existing semantics.
        return NominalProjectileMotion.launchSpeed(
            parameters.projectileData ?: parameters.data,
            parameters.level,
            parameters.shootPosition,
        ).toFloat()
    }

    private fun configureCommon(
        entity: Entity,
        parameters: ShootParameters,
        data: GunData,
        velocity: Float,
    ) {
        val shooter = parameters.shooter
        if (entity is Projectile) entity.owner = shooter
        if (entity is ProjectileEntity) {
            entity.shooter(shooter)
                .damage(data.get(GunProp.DAMAGE).toFloat())
                .headShot(data.get(GunProp.HEADSHOT).toFloat())
                .zoom(parameters.zoom)
                .bypassArmorRate(data.get(GunProp.BYPASSES_ARMOR).toFloat())
                .setGunItemId(data.stack)
                .velocity(velocity)
        }
        if (entity is CustomDamageProjectile) entity.setDamage(data.get(GunProp.DAMAGE).toFloat())
        if (entity is CustomGravityEntity) entity.setGravity(data.get(GunProp.GRAVITY).toFloat())
        if (entity is ExplosiveProjectile) {
            entity.setExplosionDamage(data.get(GunProp.EXPLOSION_DAMAGE).toFloat())
            entity.setExplosionRadius(data.get(GunProp.EXPLOSION_RADIUS).toFloat())
            entity.setLife(data.get(GunProp.PROJECTILE_LIFE))
        }
    }

    private fun configureTyped(entity: Entity, data: GunData) {
        val shellMode = ShellMode.parse(data.get(GunProp.SHELL_TYPE))
        if (entity is SmallCannonShellEntity && shellMode == ShellMode.AA) entity.antiAir(true)
        if (entity is CannonShellEntity) configureCannonShell(entity, data, shellMode)
        if (entity is MediumRocketEntity) configureMediumRocket(entity, data, shellMode)
    }

    private fun configureCannonShell(entity: CannonShellEntity, data: GunData, mode: ShellMode) {
        when (mode) {
            ShellMode.AP -> {
                entity.setType(CannonShellEntity.Type.AP)
                entity.durability(data.get(GunProp.AP_DURABILITY))
            }
            ShellMode.HE -> entity.setType(CannonShellEntity.Type.HE)
            ShellMode.CM -> entity.setType(CannonShellEntity.Type.CM)
            ShellMode.WP -> entity.setType(CannonShellEntity.Type.WP)
            else -> return
        }
        if (mode == ShellMode.CM || mode == ShellMode.WP) configureCannonSpread(entity, data)
    }

    private fun configureMediumRocket(entity: MediumRocketEntity, data: GunData, mode: ShellMode) {
        when (mode) {
            ShellMode.AP -> {
                entity.setType(MediumRocketEntity.Type.AP)
                entity.durability(data.get(GunProp.AP_DURABILITY))
            }
            ShellMode.HE -> entity.setType(MediumRocketEntity.Type.HE)
            ShellMode.CM -> entity.setType(MediumRocketEntity.Type.CM)
            else -> return
        }
        if (mode == ShellMode.CM) {
            entity.setSpreadAmount(data.get(GunProp.SPREAD_AMOUNT))
            entity.setSpreadAngle(data.get(GunProp.SPREAD_ANGLE))
        }
    }

    private fun configureCannonSpread(entity: CannonShellEntity, data: GunData) {
        entity.setSpreadAmount(data.get(GunProp.SPREAD_AMOUNT))
        entity.setSpreadAngle(data.get(GunProp.SPREAD_ANGLE))
    }

    private fun configureGuidance(entity: Entity, parameters: ShootParameters) {
        val shooter = parameters.shooter
        if (entity is WireGuideMissileEntity && shooter?.vehicle != null) {
            entity.setLauncherVehicle(shooter.vehicle!!.uuid)
            parameters.weaponGuidanceContext?.let(entity::setLauncherWeaponGuidanceContext)
        }
        if (entity is MissileProjectile && shooter != null) {
            val target = parameters.targetEntityUUID?.let {
                EntityFindUtil.findEntity(shooter.level(), it.toString())
            }
            when {
                target != null -> {
                    entity.setGuideType(ENTITY_GUIDANCE)
                    entity.setTargetUuid(target.uuid.toString())
                }
                parameters.targetPos != null -> {
                    entity.setGuideType(POSITION_GUIDANCE)
                    entity.setTargetVec(parameters.targetPos)
                }
            }
        }
        val vehicle = shooter?.vehicle as? VehicleEntity
        if (entity is SwarmDroneEntity && vehicle != null) entity.setRotate(vehicle.getTurretVector(1f))
    }

    private fun configureLaunchableData(
        entity: Entity,
        parameters: ShootParameters,
        gunData: GunData,
        projectileInfo: ProjectileInfo,
        projectileType: String,
    ) {
        val launchableInfo = projectileInfo.takeIf { it.data != null }
            ?: CustomData.LAUNCHABLE_ENTITY[projectileType]?.let { definition ->
                ProjectileInfo().apply {
                    itemId = projectileType
                    data = definition.data
                }
            }
            ?: return
        val shootData = ShootData(
            parameters.shooter?.uuid,
            gunData.get(GunProp.DAMAGE),
            gunData.get(GunProp.EXPLOSION_DAMAGE),
            gunData.get(GunProp.EXPLOSION_RADIUS),
            gunData.get(GunProp.SPREAD),
        )
        LaunchableEntityTool.getModifiedTag(launchableInfo, shootData)?.let(entity::load)
    }

    private fun applyPerks(entity: Entity, data: GunData) {
        for (type in Perk.Type.entries) {
            for (instance in data.perk.getInstances(type)) {
                instance.perk.modifyProjectile(data, instance, entity)
            }
        }
    }

    private fun applyTrajectory(entity: Entity, direction: Vec3, velocity: Float, spread: Double) {
        if (entity is Projectile) {
            entity.shoot(direction.x, direction.y, direction.z, velocity, spread.toFloat())
            return
        }
        val random = RandomSource.create()
        val movement = direction.normalize()
            .add(
                random.triangle(0.0, 0.0172275 * spread),
                random.triangle(0.0, 0.0172275 * spread),
                random.triangle(0.0, 0.0172275 * spread),
            )
            .scale(velocity.toDouble())
        entity.deltaMovement = movement
        entity.hasImpulse = true
        val horizontalDistance = movement.horizontalDistance()
        entity.yRot = (Mth.atan2(movement.x, movement.z) * 180f / Math.PI.toFloat()).toFloat()
        entity.xRot = (Mth.atan2(movement.y, horizontalDistance) * 180f / Math.PI.toFloat()).toFloat()
        entity.yRotO = entity.yRot
        entity.xRotO = entity.xRot
    }

    private enum class ShellMode {
        AP, HE, CM, WP, AA, UNKNOWN;

        companion object {
            fun parse(value: String) = entries.firstOrNull { it.name == value.trim().uppercase() } ?: UNKNOWN
        }
    }
}

internal fun ProjectileInfo.resolvedProfileId() =
    profile ?: CustomData.LAUNCHABLE_ENTITY[itemId.trim()]?.profile
