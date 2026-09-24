package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.projectile.ProjectileCalibers
import com.atsuishio.superbwarfare.api.projectile.NativeVehicleHitFeedback
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.tools.DamageTypeTool
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile

/**
 * Resolves actual server aircraft contacts before addon armor queries. Does not discover a hit,
 * change collision geometry, alter projectile damage/disposal, or create another explosion.
 * Native direct/blast follow-ups for the same struck aircraft are suppressed at its hurt boundary.
 */
object AircraftProjectileDamage {
    private val NATIVE_FOLLOWUP = ProjectileImpactResult.builder(ProjectileImpactDisposition.DEFAULT)
        .suppressNativeModuleDamage(true)
        .build()
    private val INVALID_CONTACT = ProjectileImpactResult.builder(ProjectileImpactDisposition.CONSUME).build()

    @JvmStatic
    fun isAircraft(entity: Entity?): Boolean = entity is VehicleEntity &&
        AircraftProjectileHitPolicy.appliesTo(entity.vehicleType)

    /** Null leaves non-aircraft/block/client contexts entirely on their existing resolver path. */
    @JvmStatic
    fun resolve(context: ProjectileImpactContext): ProjectileImpactResult? {
        if (context.kind != ProjectileImpactContext.Kind.ENTITY ||
            context.projectile.level().isClientSide || !isAircraft(context.target)
        ) return null
        val vehicle = context.target as VehicleEntity
        if (vehicle.level() !== context.projectile.level()) return INVALID_CONTACT
        val hit = context.hitVec
        if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return INVALID_CONTACT
        applyPhysicalHit(vehicle, context.projectile, context.damageSource ?: return INVALID_CONTACT, context)
        return NATIVE_FOLLOWUP
    }

    /**
     * Legacy/native projectile adapters that report a direct physical hit only through hurt use
     * the same policy. Null means unrelated damage; false is a handled/rejected projectile hit.
     * A nearby explosion with no prior physical contact remains the existing explosion damage.
     */
    @JvmStatic
    fun handleNativeDamage(vehicle: VehicleEntity, source: DamageSource): Boolean? {
        if (!isAircraft(vehicle)) return null
        val projectile = source.directEntity as? Projectile ?: return null
        if (projectile.level() !== vehicle.level()) return null
        val route = AircraftProjectileHitPolicy.nativeRoute(
            AircraftProjectileHitReceipts.contains(projectile.persistentData, vehicle.uuid),
            source.`is`(DamageTypeTags.IS_EXPLOSION) || source.`is`(ModDamageTypes.CUSTOM_EXPLOSION),
            source.`is`(DamageTypeTags.IS_PROJECTILE) || source.`is`(ModDamageTypes.PROJECTILE_HIT) ||
                DamageTypeTool.isGunDamage(source),
        )
        return when (route) {
            AircraftProjectileDamageRoute.UNRELATED -> null
            AircraftProjectileDamageRoute.DUPLICATE -> true
            AircraftProjectileDamageRoute.PHYSICAL_HIT ->
                if (vehicle.level().isClientSide) false else applyPhysicalHit(vehicle, projectile, source)
        }
    }

    private fun applyPhysicalHit(vehicle: VehicleEntity, projectile: Projectile, source: DamageSource,
                                 context: ProjectileImpactContext? = null): Boolean {
        if (!AircraftProjectileHitReceipts.claim(projectile.persistentData, vehicle.uuid)) return true
        // Native projectile handlers reject their launcher's mounted topology before direct damage.
        // This resolver runs earlier, so preserve that guard without changing nearby blast rules.
        if (source.entity?.rootVehicle === vehicle || projectile.owner?.rootVehicle === vehicle) return false
        val directOverride = (projectile as? AircraftProjectileDamageOverride)
            ?.aircraftDirectHitDamage(vehicle.getMaxHealth())
        val caliber = if (directOverride == null) ProjectileCalibers.resolveMillimetres(projectile) else null
        val damage = AircraftProjectileHitPolicy.resolveDamage(caliber, directOverride)
        if (damage == null) {
            // Receipt admission bounds this opt-in diagnostic to one round/aircraft pair.
            if (EliteDiagnostics.isEnabled(vehicle.level())) {
                EliteDiagnostics.record(vehicle, "aircraft_damage",
                    if (directOverride == null) "CALIBER_UNAVAILABLE" else "INVALID_PROJECTILE_POLICY",
                    "projectile", projectile.uuid, "projectile_type", projectile.type,
                    "caliber_mm", caliber)
            }
            return false
        }
        val result = vehicle.applyResolvedDamage(ResolvedVehicleDamageRequest(
            source = source,
            amount = (damage * com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponDamagePolicy.scale(projectile)).toFloat(),
            modulePolicy = ResolvedVehicleModulePolicy.SKIP_NATIVE,
        ))
        if (result.accepted) {
            // Aircraft resolve before addon armor presentation, so notify accepted-hit effects here.
            NativeVehicleHitFeedback.accepted(projectile, vehicle)
            if (context != null) {
                AircraftSurfaceModules.applyAcceptedHit(vehicle, projectile.position(), context.hitVec,
                    context.incomingVelocity, result.appliedDamage)
            } else if (vehicle.usesDetailedProjectileCollision()) {
                // Legacy hurt-only adapters lack an impact vector. Reconstruct only this tick's
                // bounded movement, and require a real common projectile intersection.
                val previous=net.minecraft.world.phys.Vec3(projectile.xo,projectile.yo,projectile.zo)
                val current=projectile.position()
                val start=if(previous.distanceToSqr(current)>1e-12) previous else current
                val end=if(previous.distanceToSqr(current)>1e-12) current else current.add(projectile.deltaMovement)
                val hit=vehicle.clipProjectile(start,end)
                if(hit!=null) AircraftSurfaceModules.applyAcceptedHit(vehicle,start,hit.point(),end.subtract(start),result.appliedDamage)
            }
        }
        if (EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "aircraft_damage", "PHYSICAL_HIT",
                "projectile", projectile.uuid, "caliber_mm", caliber,
                "damage_policy", if (directOverride == null) "CALIBER_BANDS" else "DIRECT_PROJECTILE_POLICY",
                "requested_hp", damage, "applied_hp", result.appliedDamage,
                "accepted", result.accepted, "rejection", result.rejection)
        }
        return result.accepted
    }
}
