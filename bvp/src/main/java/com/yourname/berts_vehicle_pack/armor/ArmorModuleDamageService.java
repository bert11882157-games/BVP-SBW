package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.InternalModuleHits;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.ModuleHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class ArmorModuleDamageService {
    private static final double AMMO_RACK_NEIGHBOR_GAP = 0.12D;
    /** Module the current shot already hit directly from outside (skipped by its internal ray). */
    static final ThreadLocal<String> DIRECT_MODULE = new ThreadLocal<>();

    private ArmorModuleDamageService() {
    }

    static void damageDirectModule(Entity owner, ArmorTarget target,
                                   ProjectileArmorEffect shot, ModuleHit moduleHit,
                                   boolean trackHit, Vec3 hitVec) {
        target.vehicle().markArmorPlateHit(moduleHit.hit.plate.name);
        boolean wasDestroyed = trackHit
                ? ("right".equals(moduleHit.trackSide)
                ? target.vehicle().isRightTrackBroken()
                : target.vehicle().isLeftTrackBroken())
                : target.vehicle().isModuleDestroyed(moduleHit.moduleId);
        target.vehicle().damageModule(moduleHit.moduleId, hitVec, shot.moduleDamage(moduleHit.moduleId));
        ArmorSoundService.play(target.level(), hitVec, ArmorSoundService.METAL_HIT_SOUND, 1.0F, 0.7F);
        if (trackHit) {
            boolean trackBroken = "right".equals(moduleHit.trackSide)
                    ? target.vehicle().isRightTrackBroken()
                    : target.vehicle().isLeftTrackBroken();
            ArmorImpactReporter.reportDirectTrackHit(target.level(), owner, target, hitVec, moduleHit.hit.plate,
                    moduleHit.trackSide, trackBroken, trackBroken && !wasDestroyed, shot);
        } else {
            boolean destroyed = target.vehicle().isModuleDestroyed(moduleHit.moduleId);
            ArmorImpactReporter.reportDirectModuleHit(target.level(), owner, target, hitVec, moduleHit.hit.plate,
                    moduleHit.moduleId, destroyed, destroyed && !wasDestroyed, shot);
        }
    }

    static InternalModuleDamageResult applyInternalModuleDamage(ArmorTarget target, ArmorProfile profile, Vec3 hitVec,
                                                               ProjectileArmorEffect shot,
                                                               InternalModuleHits internalHits,
                                                               Entity projectile) {
        boolean ammoRackDestroyed = false;
        Set<String> newlyDestroyed = new LinkedHashSet<>();
        if (internalHits.engineBox() != null) {
            boolean wasDestroyed = target.vehicle().isModuleDestroyed(ArmorModuleResolver.ENGINE);
            target.vehicle().damageModule(ArmorModuleResolver.ENGINE, hitVec,
                    shot.moduleDamage(ArmorModuleResolver.ENGINE));
            if (!wasDestroyed && target.vehicle().isModuleDestroyed(ArmorModuleResolver.ENGINE)) {
                newlyDestroyed.add(ArmorModuleResolver.ENGINE);
            }
            DamageDiagnostics.module(target, projectile, shot, ArmorModuleResolver.ENGINE, "internal");
        }
        if (internalHits.ammoRackBox() != null) {
            ammoRackDestroyed = applyAmmoRackDamage(target, profile, hitVec, shot, internalHits.ammoRackBox(),
                    projectile);
        }
        if (internalHits.moduleHit == null || internalHits.moduleId.isEmpty()) {
            return new InternalModuleDamageResult(null, ammoRackDestroyed, newlyDestroyed);
        }
        if (ArmorModuleResolver.isAmmoRack(internalHits.moduleId)
                || ArmorModuleResolver.ENGINE.equals(internalHits.moduleId)
                || ArmorModuleResolver.isTrack(internalHits.moduleId)
                || internalHits.moduleId.equals(DIRECT_MODULE.get())) {
            return new InternalModuleDamageResult(internalHits.moduleHit.plate, ammoRackDestroyed, newlyDestroyed);
        }
        boolean wasDestroyed = target.vehicle().isModuleDestroyed(internalHits.moduleId);
        target.vehicle().damageModule(internalHits.moduleId, hitVec, shot.moduleDamage(internalHits.moduleId));
        if (!wasDestroyed && target.vehicle().isModuleDestroyed(internalHits.moduleId)) {
            newlyDestroyed.add(ArmorModuleResolver.normalizeModuleId(internalHits.moduleId));
        }
        DamageDiagnostics.module(target, projectile, shot, internalHits.moduleId, "internal");
        return new InternalModuleDamageResult(internalHits.moduleHit.plate, ammoRackDestroyed, newlyDestroyed);
    }

    /**
     * The rack the shot crosses rolls the round's detonation chance (its AmmoRackDamage per mille, however full
     * the racks are). A rack that holds takes the round's module damage, and so do its neighbours; any rack
     * brought to 0 goes up.
     */
    private static boolean applyAmmoRackDamage(ArmorTarget target, ArmorProfile profile, Vec3 hitVec,
                                               ProjectileArmorEffect shot, ArmorBox hitBox, Entity projectile) {
        String hitAmmoRackModuleId = ArmorModuleResolver.ammoRackModuleId(hitBox);
        double chance = shot.ammoRackInstantDetonationChance();
        double roll = ThreadLocalRandom.current().nextDouble();
        if (chance > 0.0D && roll < chance) {
            target.vehicle().setModuleHealth(hitAmmoRackModuleId, 0.0D);
            DamageDiagnostics.ammoRack(target, projectile, hitAmmoRackModuleId, chance, roll, true, 0.0D);
            return true;
        }
        double damage = shot.ammoRackDamage();
        boolean ammoRackDestroyed = damageAmmoRackModule(target, hitVec, damage, hitBox);
        if (profile != null) {
            for (ArmorBox candidate : profile.ammoRacks) {
                if (candidate == hitBox || candidate.name.equals(hitBox.name)
                        || !isNeighboringAmmoRack(hitBox, candidate)) {
                    continue;
                }
                ammoRackDestroyed |= damageAmmoRackModule(target, hitVec, damage, candidate);
            }
        }
        DamageDiagnostics.ammoRack(target, projectile, hitAmmoRackModuleId, chance, roll, ammoRackDestroyed,
                target.vehicle().getModuleHealth(hitAmmoRackModuleId));
        return ammoRackDestroyed;
    }

    private static boolean damageAmmoRackModule(ArmorTarget target, Vec3 hitVec,
                                                double damage, ArmorBox ammoRack) {
        String moduleId = ArmorModuleResolver.ammoRackModuleId(ammoRack);
        boolean wasDestroyed = target.vehicle().isModuleDestroyed(moduleId);
        target.vehicle().damageModule(moduleId, hitVec, damage);
        return !wasDestroyed && target.vehicle().isModuleDestroyed(moduleId);
    }

    private static boolean isNeighboringAmmoRack(ArmorBox source, ArmorBox candidate) {
        if (!source.frame.equals(candidate.frame)) {
            return false;
        }
        double gap = source.isMesh() || candidate.isMesh()
                ? boundsGap(source.volume.bounds(), candidate.volume.bounds())
                : axisAlignedGap(source, candidate);
        return gap <= AMMO_RACK_NEIGHBOR_GAP;
    }

    /** Gap between the frame-aligned bounds of two volumes (mesh racks). */
    private static double boundsGap(double[] first, double[] second) {
        double dx = Math.max(0.0D, Math.max(first[0] - second[3], second[0] - first[3]));
        double dy = Math.max(0.0D, Math.max(first[1] - second[4], second[1] - first[4]));
        double dz = Math.max(0.0D, Math.max(first[2] - second[5], second[2] - first[5]));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Legacy box rule, kept exactly for box profiles: centers and unrotated half extents. */
    private static double axisAlignedGap(ArmorBox first, ArmorBox second) {
        double dx = Math.max(0.0D, Math.abs(first.center.x - second.center.x)
                - (first.halfSize.x + second.halfSize.x));
        double dy = Math.max(0.0D, Math.abs(first.center.y - second.center.y)
                - (first.halfSize.y + second.halfSize.y));
        double dz = Math.max(0.0D, Math.abs(first.center.z - second.center.z)
                - (first.halfSize.z + second.halfSize.z));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    record InternalModuleDamageResult(ArmorBox moduleBox, boolean ammoRackDestroyed,
                                      Set<String> newlyDestroyedModuleIds) {
        InternalModuleDamageResult {
            newlyDestroyedModuleIds = newlyDestroyedModuleIds == null || newlyDestroyedModuleIds.isEmpty()
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(newlyDestroyedModuleIds));
        }
    }
}
