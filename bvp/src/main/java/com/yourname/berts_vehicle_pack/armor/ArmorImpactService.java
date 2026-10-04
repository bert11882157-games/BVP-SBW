package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
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
import java.util.Set;

final class ArmorImpactService {
    private static final double LIGHT_ARMOR_NON_PENETRATION_DAMAGE_FRACTION = 0.02D;
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
            // With no armor at all, a dual-aspect round's HE charge always penetrates.
            shot = shot.aspectAgainst(0.0D);
            if (shot.vehicleDamage >= 0.0D) {
                // Unarmored mounts (tripods, towed guns) take the round's typed hull damage like a penetration,
                // and its blast then leaves them alone.
                com.atsuishio.superbwarfare.tools.blast.StruckVehicles.mark(projectile, target.vehicle());
                return resolvePenetratingDamage(target, damageSource, shot, false,
                        ProjectileArmorEffects.hasImpactVisual(shot));
            }
            return ProjectileArmorMutationService.continueImpact(
                    ProjectileArmorEffects.hasImpactVisual(shot), ProjectileImpactPresentationOutcome.PENETRATION);
        }

        ExplosiveReactiveArmorService.Result eraResult =
                ExplosiveReactiveArmorService.apply(volumes, trace, shot, projectile);
        if (eraResult.detonated()) {
            // The ERA block spends a dual-aspect round's HE charge; only its kinetic body goes on.
            shot = eraResult.shot().withoutHeAspect();
            trace = eraResult.trace();
            ArmorImpactReporter.reportEraHit(level, owner, target, eraResult.impactVec(),
                    eraResult.eraBox(), eraResult.originalShot(), shot, eraResult.protectionMm());
        }

        ArmorHit armorHit = volumes.armorHit(trace);
        if (armorHit != null && !armorHit.isRayHit() && targetProfile.usesArmorMesh()) {
            // A mesh is traced from the visual model: a shell ray that meets no plate went past the armor. The
            // proximity match (the nearest plate within the impact tolerance) exists for coarse box profiles only.
            armorHit = null;
        }
        // No track or wheel volumes take hits (owner 2026-09-28: side shots must never be eaten by the running
        // gear). An exposed module in front of the armor (weapon systems, launcher tube) is damaged and the shot
        // still goes on to the armor behind it: module damage never replaces hull damage.
        ModuleHit moduleHit = volumes.directModuleHit(trace);
        ModuleHit exposedHit = ArmorModuleResolver.nearestExposed(armorHit, null, moduleHit);
        ArmorImpactReporter.reportVolumeSelection(target, projectile, trace, armorHit, null, moduleHit, exposedHit);
        String directModule = null;
        if (exposedHit != null && !ArmorModuleResolver.isTrack(exposedHit.moduleId)) {
            // An exposed module has no armor in front of it, so a dual-aspect round's HE charge acts on it.
            ArmorModuleDamageService.damageDirectModule(owner, target, shot.aspectAgainst(0.0D), exposedHit,
                    false, hitVec);
            ArmorImpactStats.record(Outcome.MODULE_HIT);
            DamageDiagnostics.module(target, projectile, shot, exposedHit.moduleId, "direct");
            directModule = exposedHit.moduleId;
        }

        // the module hit directly is not hit a second time by the same shot from inside
        ArmorModuleDamageService.DIRECT_MODULE.set(directModule);
        try {
            if (armorHit == null || armorHit.plate == null) {
                return handleNoPlateHit(owner, target, targetProfile, projectile, shot, damageSource, hitVec,
                        volumes, trace);
            }
            return resolvePlateHit(owner, target, targetProfile, projectile, shot, damageSource, hitVec,
                    volumes, trace, armorHit);
        } finally {
            ArmorModuleDamageService.DIRECT_MODULE.remove();
        }
    }

    /** Resolves ricochet, penetration and internal damage against the plate on the shell ray. */
    private static ProjectileImpactResult resolvePlateHit(Entity owner, ArmorTarget target,
                                                          ArmorProfile targetProfile, Projectile projectile,
                                                          ProjectileArmorEffect shot, DamageSource damageSource,
                                                          Vec3 hitVec, BvpImpactVolumeQuery volumes,
                                                          ShotTrace trace, ArmorHit armorHit) {
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
                    armorHit.armorMm() / Math.max(0.05D,
                            Math.cos(Math.toRadians(ricochet.incidenceAngleDegrees()))),
                    shot.penetrationMm, false, false, null, null, null, false, shot,
                    ArmorImpactFeedback.Classification.RICOCHET);
            ArmorImpactStats.record(Outcome.RICOCHET);
            return ProjectileArmorMutationService.ricochetImpact(replacementVisual);
        }

        Result penetration = ArmorPenetrationService.evaluate(target, armorHit, trace, shot,
                targetProfile.minArmorMm);
        if (shot.heAspect != null) {
            // Dual-aspect round (SAPHEI-T): when the HE charge defeats the plate the hit deals HE damage (not AP);
            // when only the kinetic body does, it deals the body's AP damage; otherwise it is a non-penetration.
            Result charge = ArmorPenetrationService.evaluate(target, armorHit, trace, shot.heAspect,
                    targetProfile.minArmorMm);
            if (charge.penetrated()) {
                shot = shot.heAspect;
                penetration = charge;
                replacementVisual = ProjectileArmorEffects.hasImpactVisual(shot);
            }
        }
        if (!penetration.penetrated()) {
            if (!BvpMaterialImpactSounds.hasPresentation(projectile)) {
                ArmorSoundService.play(level, hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
            }
            ArmorImpactReporter.reportArmorHit(level, owner, target, hitVec, plate,
                    penetration.impactCosine(), penetration.effectiveArmorMm(), penetration.penetrationMm(),
                    false, false, null, null, null, false, shot);
            DamageDiagnostics.plate(target, projectile, shot, plate.name, armorHit.armorMm(),
                    penetration.effectiveArmorMm(), penetration.penetrationMm(), false);
            applyLightArmorNonPenetrationDamage(target, damageSource, shot);
            ArmorImpactStats.record(Outcome.NON_PENETRATION);
            return ProjectileArmorMutationService.blockImpact(
                    replacementVisual, ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }

        ArmorImpactStats.record(Outcome.PENETRATION);
        // The round's own blast does not hit this vehicle again: the penetrating hit carries its damage.
        com.atsuishio.superbwarfare.tools.blast.StruckVehicles.mark(projectile, target.vehicle());
        DamageDiagnostics.plate(target, projectile, shot, plate.name, armorHit.armorMm(),
                penetration.effectiveArmorMm(), penetration.penetrationMm(), true);
        if (AmmoRackService.isSuperAmmoRackOverloaded(target)) {
            return handleSuperAmmoRackPenetration(owner, target, damageSource, hitVec, plate, penetration,
                    null, shot, replacementVisual);
        }

        InternalModuleHits internalHits = volumes.internalHits(trace, armorHit);
        ArmorBox engineBox = internalHits.engineBox();
        ArmorBox ammoRack = internalHits.ammoRackBox();
        ArmorModuleDamageService.InternalModuleDamageResult moduleDamage =
                ArmorModuleDamageService.applyInternalModuleDamage(target, targetProfile, hitVec, shot, internalHits,
                        damageSource.getDirectEntity());
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

        addPenetrationBurn(target, projectile, hitVec);
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
        if (targetProfile.usesArmorMesh()) {
            // The OBB contact is coarser than the mesh: the shell ray passes the armor without touching it (beside
            // an angled cheek, past a hull edge). It is no hit, and the shell keeps flying instead of stopping at
            // an invisible wall.
            ArmorImpactStats.record(Outcome.MISS);
            ArmorImpactReporter.logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Shell ray meets no mesh armor; projectile continues.");
            return ProjectileArmorMutationService.passImpact();
        }
        if (targetProfile.unboxedHitsPenetrate) {
            // Hull steel at the profile's floor: a dual-aspect round's HE charge decides first, as on a plate.
            shot = shot.aspectAgainst(targetProfile.minArmorMm);
        }
        if (targetProfile.unboxedHitsPenetrate && shot.penetrationMm + 1.0E-4D < targetProfile.minArmorMm) {
            // A gap in the boxes is still hull steel: a round below the profile's floor stops there.
            if (!BvpMaterialImpactSounds.hasPresentation(projectile)) {
                ArmorSoundService.play(target.level(), hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
            }
            applyLightArmorNonPenetrationDamage(target, damageSource, shot);
            ArmorImpactStats.record(Outcome.NON_PENETRATION);
            return ProjectileArmorMutationService.blockImpact(
                    ProjectileArmorEffects.hasImpactVisual(shot), ProjectileImpactPresentationOutcome.NON_PENETRATION);
        }
        if (targetProfile.unboxedHitsPenetrate) {
            return resolveUnboxedHit(owner, target, targetProfile, shot, damageSource, hitVec, volumes, trace);
        }
        // Strict profile, no plate on the shell ray: "Shot missed!" non-penetration. The coarse
        // vehicle OBB accepted the contact, but the authored armor does not cover it; resolving it
        // against another plate would invent armor and an impact angle the shell never met. The
        // always-on log line below records where the armor has a gap.
        if (!BvpMaterialImpactSounds.hasPresentation(damageSource.getDirectEntity())) {
            ArmorSoundService.play(target.level(), hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.75F);
        }
        ArmorImpactStats.record(Outcome.MISS);
        ArmorImpactReporter.reportNoPlateHit(target.level(), owner, target, hitVec,
                volumes.nearestArmorToImpact(trace), trace.hullImpactFallback);
        ArmorImpactReporter.logMiss(target, targetProfile, projectile, trace,
                volumes.nearestPlateToRay(trace, Double.POSITIVE_INFINITY));
        return ProjectileArmorMutationService.blockImpact(
                false, ProjectileImpactPresentationOutcome.NON_PENETRATION);
    }

    private static ProjectileImpactResult resolveUnboxedHit(Entity owner, ArmorTarget target,
                                                            ArmorProfile targetProfile, ProjectileArmorEffect shot,
                                                            DamageSource damageSource, Vec3 hitVec,
                                                            BvpImpactVolumeQuery volumes, ShotTrace trace) {
        ArmorImpactStats.record(Outcome.FALLBACK_UNBOXED);
        ArmorImpactStats.record(Outcome.PENETRATION);
        com.atsuishio.superbwarfare.tools.blast.StruckVehicles.mark(damageSource.getDirectEntity(), target.vehicle());
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
                ArmorModuleDamageService.applyInternalModuleDamage(target, targetProfile, hitVec, shot, internalHits,
                        damageSource.getDirectEntity());
        if (moduleDamage.ammoRackDestroyed()) {
            boolean detonated = AmmoRackService.triggerAmmoRackDetonation(target, hitVec, damageSource);
            ArmorImpactReporter.reportUnboxedPenetration(owner, target, hitVec, shot,
                    internalHits.engineBox(), internalHits.ammoRackBox(), moduleDamage.moduleBox(), true,
                    moduleDamage.newlyDestroyedModuleIds());
            return ProjectileArmorMutationService.blockImpact(
                    !detonated && replacementVisual, ProjectileImpactPresentationOutcome.PENETRATION);
        }

        addPenetrationBurn(target, damageSource.getDirectEntity(), hitVec);
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

    /** Calibre-sized fire and smoke where the round went in (rounds of 20 mm and up). */
    private static void addPenetrationBurn(ArmorTarget target, Entity projectile, Vec3 hitVec) {
        var combat = projectile == null ? null
                : com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles.combatDescriptor(projectile);
        if (combat == null) {
            return;
        }
        Double calibre = combat.getDiameterMm() != null ? combat.getDiameterMm() : combat.getCaliberMm();
        if (calibre != null) {
            target.vehicle().addPenetrationBurn(hitVec, calibre);
        }
    }

    /**
     * The one hull-damage path of a penetrating hit: the round's typed HullDamage (legacy SBW shells keep their
     * shell damage). Chemical rounds are no different from kinetic ones here.
     */
    private static ProjectileImpactResult resolvePenetratingDamage(ArmorTarget target,
                                                                   DamageSource damageSource,
                                                                   ProjectileArmorEffect shot,
                                                                   boolean criticalHit,
                                                                   boolean replacementVisual) {
        if (shot.vehicleDamage >= 0.0D) {
            VehicleDamageService.applyVehicleDamage(
                    target, damageSource, shot.vehicleDamage);
            DamageDiagnostics.hull(target, damageSource, shot, shot.vehicleDamage, "penetration");
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
        double damage = damageBasis * LIGHT_ARMOR_NON_PENETRATION_DAMAGE_FRACTION;
        VehicleDamageService.applyVehicleDamage(target, damageSource, damage);
        if (damage > 0.0D) {
            com.atsuishio.superbwarfare.tools.blast.StruckVehicles.mark(damageSource.getDirectEntity(), target.vehicle());
        }
        DamageDiagnostics.hull(target, damageSource, shot, damage, "light_armor_non_penetration");
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
        return 0.0D;
    }
}
