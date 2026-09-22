package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.InternalModuleHits;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.ModuleHit;
import com.yourname.berts_vehicle_pack.armor.ArmorPenetrationService.Result;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class ArmorImpactService {
    private static final double LIGHT_ARMOR_NON_PENETRATION_DAMAGE_FRACTION = 0.15D;

    ProjectileImpactResult handle(ProjectileImpactContext context, BvpImpactVolumeQuery volumes) {
        ArmorTarget target = volumes.target();
        Level level = target.level();
        if (level.f_46443_) {
            return ProjectileImpactResult.defaultResult();
        }

        Projectile projectile = context.getProjectile();
        DamageSource damageSource = context.getDamageSource();
        if (damageSource == null || !target.vehicle().acceptsDamageSource(damageSource)) {
            return ProjectileImpactResult.defaultResult();
        }
        Vec3 hitVec = context.getHitVec();
        return handleImpact(context.getOwner(), volumes, projectile, damageSource, hitVec, volumes.initialTrace());
    }

    private ProjectileImpactResult handleImpact(Entity owner, BvpImpactVolumeQuery volumes,
                                                Projectile projectile, DamageSource damageSource,
                                                Vec3 hitVec, ShotTrace trace) {
        ArmorTarget target = volumes.target();
        Level level = target.level();
        ArmorProfile targetProfile = volumes.profile();
        ProjectileArmorEffect shot = ArmorShotClassifier.classify(projectile, owner, targetProfile, hitVec);
        if (shot == null) {
            return handleUnclassifiedProjectile(owner, target, projectile, targetProfile, hitVec);
        }

        if (!targetProfile.hasImpactVolumes()) {
            ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Armor ignored: target profile has no armor plates.");
            return ProjectileArmorMutationService.continueImpact(
                    ProjectileArmorEffects.hasImpactVisual(shot), ProjectileImpactPresentationOutcome.PENETRATION);
        }

        ExplosiveReactiveArmorService.Result eraResult =
                ExplosiveReactiveArmorService.apply(volumes, trace, shot);
        if (eraResult.detonated()) {
            shot = eraResult.shot();
            trace = eraResult.trace();
            ArmorImpactReporter.reportEraHit(level, owner, target, eraResult.impactVec(),
                    eraResult.eraBox(), eraResult.originalShot(), shot, eraResult.protectionMm());
        }

        ArmorHit armorHit = volumes.armorHit(trace);
        ModuleHit trackHit = volumes.directTrackHit(trace);
        if (isExposedModuleHit(trackHit, armorHit)) {
            return handleDirectModuleHit(owner, target, shot, trackHit, true, hitVec);
        }

        ModuleHit moduleHit = volumes.directModuleHit(trace);
        if (isExposedModuleHit(moduleHit, armorHit)) {
            // A profile may expose a track through its generic module volume instead of the
            // dedicated track list. Preserve track damage/reporting semantics by identity, not
            // by which cache populated the ModuleHit.
            return handleDirectModuleHit(owner, target, shot, moduleHit,
                    ArmorModuleResolver.isTrack(moduleHit.moduleId), hitVec);
        }

        ArmorBox plate = armorHit == null ? null : armorHit.plate;
        if (plate == null) {
            return handleNoPlateHit(owner, target, targetProfile, shot, damageSource, hitVec,
                    volumes, trace, volumes.nearestArmorToImpact(trace), trace.hullImpactFallback);
        }
        target.vehicle().markArmorPlateHit(plate.name);

        // Ricochet is an authoritative pre-penetration decision.  It requires the exact typed
        // belt-round identity plus its WT-derived incidence curve; absent/malformed metadata
        // deliberately follows the existing penetration path rather than guessing by caliber.
        ArmorRicochetService.Decision ricochet = ArmorRicochetService.evaluate(
                projectile, target, armorHit, trace);
        boolean replacementVisual = ProjectileArmorEffects.hasImpactVisual(shot);
        if (ricochet.repeated()) {
            // A reflected round that immediately re-enters the same plate is consumed without a
            // second damage/effect decision; this closes same-plate bounce loops deterministically.
            return ProjectileArmorMutationService.blockImpact(
                    false, ProjectileImpactPresentationOutcome.DEFAULT);
        }
        if (ricochet.ricochet() && ArmorRicochetService.applyDeflection(
                projectile, target, armorHit, trace)) {
            ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                    Math.cos(Math.toRadians(ricochet.incidenceAngleDegrees())),
                    plate.armorMm / Math.max(0.05D,
                            Math.cos(Math.toRadians(ricochet.incidenceAngleDegrees()))),
                    shot.penetrationMm, false, false, null, null, null, false, shot,
                    ArmorImpactFeedback.Classification.RICOCHET);
            return ProjectileArmorMutationService.ricochetImpact(replacementVisual);
        }

        Result penetration = ArmorPenetrationService.evaluate(target, armorHit, trace, shot);
        if (!penetration.penetrated()) {
            ArmorSoundService.play(level, hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
            ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                    penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                    false, false, null, null, null, false, shot);
            applyLightArmorNonPenetrationDamage(target, damageSource, shot);
            return ProjectileArmorMutationService.blockImpact(
                    replacementVisual, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }

        if (AmmoRackService.isSuperAmmoRackOverloaded(target)) {
            return handleSuperAmmoRackPenetration(owner, target, damageSource, hitVec, plate, penetration,
                    null, shot, replacementVisual);
        }

        InternalModuleHits internalHits = volumes.internalHits(trace, armorHit);
        ArmorBox engineBox = internalHits.engineBox();
        ArmorBox ammoRack = internalHits.ammoRackBox();
        ArmorModuleDamageService.InternalModuleDamageResult moduleDamage =
                ArmorModuleDamageService.applyInternalModuleDamage(target, targetProfile, hitVec, shot, internalHits);
        ArmorBox genericModuleBox = moduleDamage.moduleBox();
        if (moduleDamage.ammoRackDestroyed()) {
            boolean detonated = AmmoRackService.triggerAmmoRackDetonation(target, hitVec, damageSource);
            if (!detonated) {
                ArmorSoundService.play(level, hitVec, ArmorSoundService.PENETRATION_SOUND, 1.65F, 0.65F);
            }
            ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                    penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                    true, internalHits.sensitiveInternal, engineBox, ammoRack, genericModuleBox, true, shot,
                    ArmorImpactFeedback.Classification.PENETRATION,
                    moduleDamage.newlyDestroyedModuleIds());
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        boolean criticalHit = internalHits.criticalHit();
        ProjectileImpactResult impactResult = resolvePenetratingDamage(
                target, damageSource, shot, criticalHit, replacementVisual);
        ArmorSoundService.play(level, hitVec, ArmorSoundService.PENETRATION_SOUND,
                criticalHit ? 1.25F : 1.0F,
                criticalHit ? 0.8F : 1.0F);
        ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                true, internalHits.sensitiveInternal, engineBox, ammoRack, genericModuleBox, false, shot,
                ArmorImpactFeedback.Classification.PENETRATION,
                moduleDamage.newlyDestroyedModuleIds());
        return impactResult;
    }

    private static ProjectileImpactResult handleDirectModuleHit(Entity owner, ArmorTarget target,
                                                                ProjectileArmorEffect shot,
                                                                ModuleHit moduleHit, boolean trackHit,
                                                                Vec3 hitVec) {
        ArmorModuleDamageService.damageDirectModule(owner, target, shot, moduleHit, trackHit, hitVec);
        return ProjectileArmorMutationService.blockImpact(
                ProjectileArmorEffects.hasImpactVisual(shot), ProjectileImpactPresentationOutcome.NON_PENETRATION);
    }

    private static boolean isExposedModuleHit(ModuleHit moduleHit, ArmorHit armorHit) {
        return moduleHit != null && (armorHit == null || moduleHit.hit.distance < armorHit.distance - 1.0E-4D);
    }

    private static ProjectileImpactResult handleSuperAmmoRackPenetration(Entity owner,
                                                                         ArmorTarget target,
                                                                         DamageSource damageSource, Vec3 hitVec,
                                                                         ArmorBox plate, Result penetration,
                                                                         ArmorBox moduleBox,
                                                                         ProjectileArmorEffect shot,
                                                                         boolean replacementVisual) {
        boolean detonated = AmmoRackService.triggerSuperAmmoRackDetonation(target, hitVec, damageSource);
        ArmorImpactReporter.reportArmorHit(target.level(), owner, target, hitVec, plate,
                penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                true, false, null, null, moduleBox, true, shot);
        return ProjectileArmorMutationService.blockImpact(
                !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
    }

    private static ProjectileImpactResult handleUnclassifiedProjectile(Entity owner, ArmorTarget target,
                                                                       Projectile projectile,
                                                                       ArmorProfile targetProfile,
                                                                       Vec3 hitVec) {
        ProjectileArmorEffect visual = ArmorShotClassifier.classifyUnmodeledBvpImpact(projectile, owner);
        if (target.strictArmorGate(targetProfile)) {
            ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Armor blocked: projectile has no BVP penetration model.");
            return ProjectileArmorMutationService.blockImpact(
                    ProjectileArmorEffects.hasImpactVisual(visual), ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }
        ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                "[BVP Armor] Armor ignored: projectile has no BVP penetration model.");
        return ProjectileArmorMutationService.continueImpact(
                ProjectileArmorEffects.hasImpactVisual(visual), ProjectileImpactPresentationOutcome.PENETRATION);
    }

    private static ProjectileImpactResult handleNoPlateHit(Entity owner, ArmorTarget target,
                                                           ArmorProfile targetProfile, ProjectileArmorEffect shot,
                                                           DamageSource damageSource, Vec3 hitVec,
                                                           BvpImpactVolumeQuery volumes, ShotTrace trace,
                                                           ArmorHitResolver.NearBox nearestPlate,
                                                           ArmorProfiles.Vec localImpact) {
        if (!targetProfile.unboxedHitsPenetrate) {
            ArmorSoundService.play(target.level(), hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
            ArmorImpactReporter.reportNoPlateHit(target.level(), owner, target, hitVec,
                    nearestPlate, localImpact);
            return ProjectileArmorMutationService.blockImpact(
                    false, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }
        boolean replacementVisual = ProjectileArmorEffects.hasImpactVisual(shot);
        if (AmmoRackService.isSuperAmmoRackOverloaded(target)) {
            boolean detonated = AmmoRackService.triggerSuperAmmoRackDetonation(target, hitVec, damageSource);
            ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Round penetrated unboxed armor while more than 50 cannon shells were loaded. Super ammo rack detonated.");
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        InternalModuleHits internalHits = volumes.internalHits(trace, null);
        ArmorModuleDamageService.InternalModuleDamageResult moduleDamage =
                ArmorModuleDamageService.applyInternalModuleDamage(target, targetProfile, hitVec, shot, internalHits);
        if (moduleDamage.ammoRackDestroyed()) {
            boolean detonated = AmmoRackService.triggerAmmoRackDetonation(target, hitVec, damageSource);
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        return resolvePenetratingDamage(
                target, damageSource, shot, internalHits.criticalHit(), replacementVisual);
    }

    private static ProjectileImpactResult resolvePenetratingDamage(ArmorTarget target,
                                                                   DamageSource damageSource,
                                                                   ProjectileArmorEffect shot,
                                                                   boolean criticalHit,
                                                                   boolean replacementVisual) {
        if (shot.vehicleDamage >= 0.0D) {
            VehicleDamageService.applyVehicleDamage(
                    target, damageSource, shot.vehicleDamage);
            return ProjectileArmorMutationService.blockImpact(
                    replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }
        if (shot.shell != null) {
            return ProjectileArmorMutationService.penetratingShellResult(
                    shot.shell,
                    shot.damageType,
                    criticalHit,
                    replacementVisual
            );
        }
        if (shot.damageType == ArmorDamageType.CHEMICAL) {
            VehicleDamageService.applyVehicleDamage(
                    target,
                    damageSource,
                    VehicleDamageService.chemicalVehicleDamageBasis(target, criticalHit)
            );
            return ProjectileArmorMutationService.blockImpact(
                    replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }
        return ProjectileArmorMutationService.continueImpact(
                replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
    }

    private static void applyLightArmorNonPenetrationDamage(ArmorTarget target,
                                                             DamageSource damageSource,
                                                             ProjectileArmorEffect shot) {
        if (!target.vehicle().isLightlyArmored()) {
            return;
        }
        double damageBasis = nonCriticalDirectDamageBasis(target, damageSource, shot);
        VehicleDamageService.applyVehicleDamage(
                target,
                damageSource,
                damageBasis * LIGHT_ARMOR_NON_PENETRATION_DAMAGE_FRACTION
        );
    }

    private static double nonCriticalDirectDamageBasis(ArmorTarget target,
                                                        DamageSource damageSource,
                                                        ProjectileArmorEffect shot) {
        if (shot.vehicleDamage >= 0.0D) {
            return shot.vehicleDamage;
        }
        if (shot.shell != null) {
            float rawDamage = ProjectileArmorMutationService.penetratingShellDamage(
                    shot.shell, shot.damageType, false);
            return target.vehicle().computeVehicleDamageAfterModifiers(damageSource, rawDamage);
        }
        if (shot.damageType == ArmorDamageType.CHEMICAL) {
            return VehicleDamageService.chemicalVehicleDamageBasis(target, false);
        }
        return 0.0D;
    }

}
