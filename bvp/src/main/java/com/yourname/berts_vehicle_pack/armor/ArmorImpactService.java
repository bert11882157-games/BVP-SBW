package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.RaySnap;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorImpactStats.Outcome;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.InternalModuleHits;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.ModuleHit;
import com.yourname.berts_vehicle_pack.armor.ArmorPenetrationService.Result;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ArmorImpactService {
    private static final double LIGHT_ARMOR_NON_PENETRATION_DAMAGE_FRACTION = 0.02D;
    /** Strict profiles resolve a plate-free contact against a plate at most this far from the shot ray. */
    static final double NEAREST_PLATE_MAX_GAP_BLOCKS = 1.5D;
    private static final double CREW_CONTACT_BACKTRACE_BLOCKS = 1.0D;
    private static final double CREW_CONTACT_FORWARD_BLOCKS = 8.0D;

    ProjectileImpactResult handle(ProjectileImpactContext context, BvpImpactVolumeQuery volumes) {
        ArmorTarget target = volumes.target();
        Level level = target.level();
        if (level.f_46443_) {
            return ProjectileImpactResult.defaultResult();
        }

        Projectile projectile = context.getProjectile();
        if (hiddenCrewContactMissesHull(context, target)) {
            // Hidden crew boxes can protrude above a turret. A contact there is not a hull hit:
            // the projectile keeps flying instead of being resolved against armor it never reached.
            ArmorImpactStats.record(Outcome.PASSENGER_REDIRECT_SKIPPED);
            ArmorImpactReporter.logArmorEvent(context.getOwner(), target, context.getHitVec(),
                    "[BVP Armor] Hidden crew contact lies outside every vehicle OBB; projectile continues.");
            return ProjectileArmorMutationService.passImpact();
        }
        DamageSource damageSource = context.getDamageSource();
        if (damageSource == null || !target.vehicle().acceptsDamageSource(damageSource)) {
            return ProjectileImpactResult.defaultResult();
        }
        Vec3 hitVec = context.getHitVec();
        if (!EliteDiagnostics.isEnabled(level)) {
            return handleImpact(context.getOwner(), volumes, projectile, damageSource, hitVec, volumes.initialTrace());
        }
        double leftBefore = target.vehicle().getModuleHealth("lefttrack");
        double rightBefore = target.vehicle().getModuleHealth("righttrack");
        double hullBefore = target.vehicle().getHealth();
        ProjectileImpactResult result = handleImpact(context.getOwner(), volumes, projectile,
                damageSource, hitVec, volumes.initialTrace());
        EliteDiagnostics.record(target.vehicle(), "hitreg", "armor_commit",
                "projectile", projectile.m_20148_(), "profile", target.armorProfileId(),
                "left_before", leftBefore, "left_after", target.vehicle().getModuleHealth("lefttrack"),
                "right_before", rightBefore, "right_after", target.vehicle().getModuleHealth("righttrack"),
                "hull_before", hullBefore, "hull_after", target.vehicle().getHealth(),
                "disposition", result.getDisposition(), "outcome", result.getPresentationOutcome());
        return result;
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
            ArmorImpactStats.record(Outcome.PENETRATION);
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
        ModuleHit moduleHit = volumes.directModuleHit(trace);
        ModuleHit exposedHit = ArmorModuleResolver.nearestExposed(armorHit, trackHit, moduleHit);
        ArmorImpactReporter.reportVolumeSelection(target, projectile, trace, armorHit, trackHit, moduleHit, exposedHit);
        if (exposedHit != null) {
            return handleDirectModuleHit(owner, target, shot, exposedHit,
                    ArmorModuleResolver.isTrack(exposedHit.moduleId), hitVec);
        }

        if (armorHit == null || armorHit.plate == null) {
            return handleNoPlateHit(owner, target, targetProfile, projectile, shot, damageSource, hitVec,
                    volumes, trace);
        }
        return resolvePlateHit(owner, target, targetProfile, projectile, shot, damageSource, hitVec,
                volumes, trace, armorHit, armorHit);
    }

    /**
     * Resolves ricochet, penetration and internal damage against one plate. {@code internalOrigin}
     * is the contact internal module rays start from; null starts them at the accepted contact.
     */
    private static ProjectileImpactResult resolvePlateHit(Entity owner, ArmorTarget target,
                                                          ArmorProfile targetProfile, Projectile projectile,
                                                          ProjectileArmorEffect shot, DamageSource damageSource,
                                                          Vec3 hitVec, BvpImpactVolumeQuery volumes,
                                                          ShotTrace trace, ArmorHit armorHit,
                                                          ArmorHit internalOrigin) {
        Level level = target.level();
        ArmorBox plate = armorHit.plate;
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
            ArmorImpactStats.record(Outcome.RICOCHET_REENTRY);
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
            ArmorImpactStats.record(Outcome.RICOCHET);
            return ProjectileArmorMutationService.ricochetImpact(replacementVisual);
        }

        Result penetration = ArmorPenetrationService.evaluate(target, armorHit, trace, shot);
        if (!penetration.penetrated()) {
            if (!BvpMaterialImpactSounds.hasPresentation(projectile)) {
                ArmorSoundService.play(level, hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
            }
            ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                    penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                    false, false, null, null, null, false, shot);
            applyLightArmorNonPenetrationDamage(target, damageSource, shot);
            ArmorImpactStats.record(Outcome.NON_PENETRATION);
            return ProjectileArmorMutationService.blockImpact(
                    replacementVisual, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }

        ArmorImpactStats.record(Outcome.PENETRATION);
        if (AmmoRackService.isSuperAmmoRackOverloaded(target)) {
            return handleSuperAmmoRackPenetration(owner, target, damageSource, hitVec, plate, penetration,
                    null, shot, replacementVisual);
        }

        InternalModuleHits internalHits = volumes.internalHits(trace, internalOrigin);
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
        ArmorImpactStats.record(trackHit ? Outcome.TRACK_HIT : Outcome.MODULE_HIT);
        return ProjectileArmorMutationService.blockImpact(
                ProjectileArmorEffects.hasImpactVisual(shot), ProjectileImpactPresentationOutcome.NON_PENETRATION);
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
            ArmorImpactStats.record(Outcome.UNMODELED_BLOCK);
            return ProjectileArmorMutationService.blockImpact(
                    ProjectileArmorEffects.hasImpactVisual(visual), ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }
        ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                "[BVP Armor] Armor ignored: projectile has no BVP penetration model.");
        ArmorImpactStats.record(Outcome.PENETRATION);
        return ProjectileArmorMutationService.continueImpact(
                ProjectileArmorEffects.hasImpactVisual(visual), ProjectileImpactPresentationOutcome.PENETRATION);
    }

    private static ProjectileImpactResult handleNoPlateHit(Entity owner, ArmorTarget target,
                                                           ArmorProfile targetProfile, Projectile projectile,
                                                           ProjectileArmorEffect shot, DamageSource damageSource,
                                                           Vec3 hitVec, BvpImpactVolumeQuery volumes,
                                                           ShotTrace trace) {
        if (targetProfile.unboxedHitsPenetrate) {
            return resolveUnboxedHit(owner, target, targetProfile, shot, damageSource, hitVec, volumes, trace);
        }
        // The projectile is already inside a vehicle OBB. Strict profiles must never turn that
        // accepted contact into a silent miss: resolve it against the plate the shell passed
        // closest to, or as an unboxed hull hit when no plate is near the ray at all.
        RaySnap snap = volumes.nearestPlateToRay(trace, NEAREST_PLATE_MAX_GAP_BLOCKS);
        RunningGearHit runningGear = damageRunningGear(target, targetProfile, shot, trace, snap, hitVec);
        ProjectileImpactResult result;
        if (snap != null) {
            ArmorImpactStats.record(Outcome.FALLBACK_NEAREST_PLATE);
            if (EliteDiagnostics.isEnabled(target.level())) {
                ArmorImpactReporter.logArmorEvent(owner, target, hitVec, String.format(Locale.ROOT,
                        "[BVP Armor] No plate on the shell ray; resolved against nearest plate %s (%s frame, %.2f blocks from the ray).",
                        snap.hit().plate.name, snap.hit().plate.frame, snap.gap()));
            }
            result = resolvePlateHit(owner, target, targetProfile, projectile, shot, damageSource, hitVec,
                    volumes, trace, snap.hit(), null);
        } else {
            if (EliteDiagnostics.isEnabled(target.level())) {
                ArmorHitResolver.NearBox nearest = volumes.nearestArmorToImpact(trace);
                ArmorImpactReporter.logArmorEvent(owner, target, hitVec, String.format(Locale.ROOT,
                        "[BVP Armor] No plate within %.1f blocks of the shell ray; resolved as unboxed hull. Nearest armor: %s",
                        NEAREST_PLATE_MAX_GAP_BLOCKS,
                        nearest == null ? "none" : String.format(Locale.ROOT, "%s dist %.2f",
                                nearest.box().name, nearest.distance())));
            }
            result = resolveUnboxedHit(owner, target, targetProfile, shot, damageSource, hitVec, volumes, trace);
        }
        if (runningGear != null
                && result.getPresentationOutcome() != ProjectileImpactPresentationOutcome.PENETRATION
                && result.getPresentationOutcome() != ProjectileImpactPresentationOutcome.RICOCHET) {
            // The hull held, but the running gear did not: show the track damage to the shooter.
            ArmorImpactReporter.reportRunningGearHit(owner, target, hitVec, runningGear.side(),
                    runningGear.broken(), runningGear.newlyBroken(), shot);
        }
        return result;
    }

    /**
     * Damages the struck track side when the contact lies below the armor on a vehicle with track
     * modules. The armor floor is the nearest hull plate's lowest point, or the lowest hull plate
     * when no plate is near the ray; contacts beside turret plates are never running gear.
     */
    private static RunningGearHit damageRunningGear(ArmorTarget target, ArmorProfile profile,
                                                    ProjectileArmorEffect shot, ShotTrace trace,
                                                    RaySnap snap, Vec3 hitVec) {
        ArmoredVehicleEntity vehicle = target.vehicle();
        if (!vehicle.usesBvpTrackModuleRepair()) {
            return null;
        }
        double armorFloor = Double.NaN;
        if (snap != null) {
            ArmorBox plate = snap.hit().plate;
            if (!plate.isTurretFrame() && !plate.isBarrelFrame()) {
                armorFloor = plate.minFrameY();
            }
        } else {
            armorFloor = lowestHullPlateY(profile);
        }
        if (!Double.isFinite(armorFloor) || !(trace.hullImpactFallback.y < armorFloor)) {
            return null;
        }
        String side = ArmorModuleResolver.trackSide(target, trace.hullImpactFallback);
        boolean right = "right".equals(side);
        String moduleId = right ? ArmorModuleResolver.RIGHT_TRACK : ArmorModuleResolver.LEFT_TRACK;
        boolean wasBroken = right ? vehicle.isRightTrackBroken() : vehicle.isLeftTrackBroken();
        vehicle.damageModule(moduleId, hitVec, shot.moduleDamage(moduleId));
        boolean broken = right ? vehicle.isRightTrackBroken() : vehicle.isLeftTrackBroken();
        ArmorImpactStats.record(Outcome.RUNNING_GEAR);
        return new RunningGearHit(side, broken, broken && !wasBroken);
    }

    private static double lowestHullPlateY(ArmorProfile profile) {
        double lowest = Double.NaN;
        for (ArmorBox plate : profile.plates) {
            if (plate.isTurretFrame() || plate.isBarrelFrame()) continue;
            double y = plate.minFrameY();
            if (Double.isFinite(y) && !(y >= lowest)) lowest = y;
        }
        return lowest;
    }

    private static ProjectileImpactResult resolveUnboxedHit(Entity owner, ArmorTarget target,
                                                            ArmorProfile targetProfile, ProjectileArmorEffect shot,
                                                            DamageSource damageSource, Vec3 hitVec,
                                                            BvpImpactVolumeQuery volumes, ShotTrace trace) {
        ArmorImpactStats.record(Outcome.FALLBACK_UNBOXED);
        ArmorImpactStats.record(Outcome.PENETRATION);
        boolean replacementVisual = ProjectileArmorEffects.hasImpactVisual(shot);
        if (AmmoRackService.isSuperAmmoRackOverloaded(target)) {
            boolean detonated = AmmoRackService.triggerSuperAmmoRackDetonation(target, hitVec, damageSource);
            ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Round penetrated unboxed armor while more than 50 cannon shells were loaded. Super ammo rack detonated.");
            ArmorImpactReporter.reportUnboxedPenetration(owner, target, hitVec, shot,
                    null, null, null, true, Set.of());
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        InternalModuleHits internalHits = volumes.internalHits(trace, null);
        ArmorModuleDamageService.InternalModuleDamageResult moduleDamage =
                ArmorModuleDamageService.applyInternalModuleDamage(target, targetProfile, hitVec, shot, internalHits);
        if (moduleDamage.ammoRackDestroyed()) {
            boolean detonated = AmmoRackService.triggerAmmoRackDetonation(target, hitVec, damageSource);
            ArmorImpactReporter.reportUnboxedPenetration(owner, target, hitVec, shot,
                    internalHits.engineBox(), internalHits.ammoRackBox(), moduleDamage.moduleBox(), true,
                    moduleDamage.newlyDestroyedModuleIds());
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        ProjectileImpactResult result = resolvePenetratingDamage(
                target, damageSource, shot, internalHits.criticalHit(), replacementVisual);
        ArmorImpactReporter.reportUnboxedPenetration(owner, target, hitVec, shot,
                internalHits.engineBox(), internalHits.ammoRackBox(), moduleDamage.moduleBox(), false,
                moduleDamage.newlyDestroyedModuleIds());
        return result;
    }

    /**
     * A contact on a hidden (non-exposed) passenger is only a vehicle hit when the shot actually
     * reaches one of the vehicle's OBBs; crew boxes can protrude above turrets and roofs.
     */
    private static boolean hiddenCrewContactMissesHull(ProjectileImpactContext context, ArmorTarget target) {
        Entity struck = context.getTarget();
        ArmoredVehicleEntity vehicle = target.vehicle();
        if (struck == null || struck == vehicle || struck.m_20202_() != vehicle
                || !ArmorTargetAdapters.isHiddenCrew(vehicle, struck)) {
            return false;
        }
        List<OBB> hull = vehicle.getOBBs();
        Vec3 hitVec = context.getHitVec();
        if (hull == null || hull.isEmpty() || hitVec == null) {
            return false;
        }
        Vec3 direction = ArmorHitResolver.shotDirection(context.getProjectile(), hitVec);
        Vec3 start = hitVec.m_82546_(direction.m_82490_(CREW_CONTACT_BACKTRACE_BLOCKS));
        Vec3 end = hitVec.m_82549_(direction.m_82490_(CREW_CONTACT_FORWARD_BLOCKS));
        return ProjectileHitSelection.nearestObb(hull, start, end, 0.0D) == null;
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

    private record RunningGearHit(String side, boolean broken, boolean newlyBroken) {
    }
}
