package com.atsuishio.superbwarfare.api.vehicle.damage

import com.atsuishio.superbwarfare.api.projectile.ProjectileCalibers
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.tools.DamageTypeTool
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.projectile.Projectile

/** A direct cannon strike destroys opted-in fragile platforms before armor/module allocation. */
object LightPlatformProjectileDamage {
    private const val RECEIPTS = "SBWLightPlatformHits"
    private val NATIVE_EFFECTS = ProjectileImpactResult.builder(ProjectileImpactDisposition.DEFAULT)
        .suppressNativeModuleDamage(true).build()

    @JvmStatic
    fun resolve(context: ProjectileImpactContext): ProjectileImpactResult? {
        if (context.kind != ProjectileImpactContext.Kind.ENTITY || context.projectile.level().isClientSide) return null
        val vehicle = context.target as? VehicleEntity ?: return null
        val projectile = context.projectile as? Projectile ?: return null
        val source = context.damageSource ?: return null
        if (!qualifies(vehicle, projectile)) return null
        if (source.entity?.rootVehicle === vehicle || projectile.owner?.rootVehicle === vehicle) return null
        if (vehicle.level() !== projectile.level()) return null
        val hit = context.hitVec
        if (!hit.x.isFinite() || !hit.y.isFinite() || !hit.z.isFinite()) return null
        apply(vehicle, projectile, source)
        // Preserve the original projectile's impact effects and disposal, without charging twice.
        return NATIVE_EFFECTS
    }

    @JvmStatic
    fun handleNativeDamage(vehicle: VehicleEntity, source: DamageSource): Boolean? {
        if (vehicle.computed().lethalDirectCaliberMm == null) return null
        val projectile = source.directEntity as? Projectile ?: return null
        if (projectile.persistentData.getCompound(RECEIPTS).contains(vehicle.uuid.toString())) return true
        if (source.`is`(DamageTypeTags.IS_EXPLOSION) || source.`is`(ModDamageTypes.CUSTOM_EXPLOSION)) return null
        if (!(source.`is`(DamageTypeTags.IS_PROJECTILE) || source.`is`(ModDamageTypes.PROJECTILE_HIT)
                || DamageTypeTool.isGunDamage(source)) || !qualifies(vehicle, projectile)) return null
        if (source.entity?.rootVehicle === vehicle || projectile.owner?.rootVehicle === vehicle) return false
        return if (vehicle.level().isClientSide) false else apply(vehicle, projectile, source)
    }

    private fun qualifies(vehicle: VehicleEntity, projectile: Projectile): Boolean {
        val threshold = vehicle.computed().lethalDirectCaliberMm ?: return false
        val caliber = ProjectileCalibers.resolveMillimetres(projectile) ?: return false
        return threshold.isFinite() && threshold > 0 && caliber.isFinite() && caliber >= threshold
    }

    private fun apply(vehicle: VehicleEntity, projectile: Projectile, source: DamageSource): Boolean {
        val receipts = projectile.persistentData.getCompound(RECEIPTS)
        val key = vehicle.uuid.toString()
        if (receipts.contains(key)) return true
        if (receipts.size() >= 64) return false
        val result = vehicle.applyResolvedDamage(ResolvedVehicleDamageRequest(
            source, vehicle.computed().maxHealth.coerceAtLeast(1f),
            modulePolicy = ResolvedVehicleModulePolicy.SKIP_NATIVE, lethal = true))
        if (result.accepted) {
            receipts.putBoolean(key, true)
            projectile.persistentData.put(RECEIPTS, receipts)
        }
        if (EliteDiagnostics.isEnabled(vehicle.level())) EliteDiagnostics.record(vehicle,
            "light_platform_damage", "DIRECT_CANNON_HIT", "projectile", projectile.uuid,
            "caliber_mm", ProjectileCalibers.resolveMillimetres(projectile),
            "accepted", result.accepted, "destroyed", result.destroyed, "applied_hp", result.appliedDamage)
        return result.accepted
    }
}
