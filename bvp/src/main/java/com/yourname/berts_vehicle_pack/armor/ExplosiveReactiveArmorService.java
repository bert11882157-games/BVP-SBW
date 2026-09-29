package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.effects.BvpLeanImpactEffects;
import net.minecraft.world.phys.Vec3;

final class ExplosiveReactiveArmorService {
    private static final double ERA_TRIGGER_THRESHOLD_MM = 50.0D;
    private static final double KONTAKT1_ATGM_CHEMICAL_PROTECTION_MM = 625.0D;
    private static final double ERA_IMPACT_TOLERANCE_MIN = 0.45D;
    private static final double SPENT_ERA_SUPPRESSION_TOLERANCE = 0.08D;
    private static final double RAY_RESTART_OFFSET_BLOCKS = 0.03D;

    private ExplosiveReactiveArmorService() {
    }

    static Result apply(BvpImpactVolumeQuery volumes, ShotTrace trace, ProjectileArmorEffect shot) {
        return apply(volumes, trace, shot, null);
    }

    /** As above; {@code projectile} names the round on the ERA diagnostics line. */
    static Result apply(BvpImpactVolumeQuery volumes, ShotTrace trace, ProjectileArmorEffect shot,
                        net.minecraft.world.entity.Entity projectile) {
        ArmorTarget target = volumes.target();
        ArmorProfile profile = volumes.profile();
        if (profile.eraBoxes.isEmpty() || shot.penetrationMm <= ERA_TRIGGER_THRESHOLD_MM) {
            return Result.unchanged(trace, shot);
        }
        ArmorHit eraHit = findActiveEraHit(volumes, trace);
        if (eraHit == null) {
            return Result.unchanged(trace, shot);
        }

        ArmorBox era = eraHit.plate;
        double protectionMm = protectionAgainst(era, shot);
        ProjectileArmorEffect reducedShot = shot.withPenetration(postEraPenetration(shot, protectionMm));
        double appliedProtectionMm = shot.penetrationMm - reducedShot.penetrationMm;
        Vec direction = trace.hullShotDirection.normalize();
        Vec restart = eraHit.hullImpact.add(direction.scale(RAY_RESTART_OFFSET_BLOCKS));
        // The plate ray keeps the normal backtrace: the accepted contact can sit behind the outer
        // face of the plate carrying the brick, and a ray started at the contact would skip it.
        Vec rayStart = restart.subtract(direction.scale(ArmorHitResolver.ARMOR_RAY_BACKTRACE_BLOCKS));
        ShotTrace reducedTrace = new ShotTrace(trace.hitVec, trace.hullShotDirection, rayStart, restart);

        Vec3 eraImpact = target.armorLocalPointToWorld(eraHit.hullImpact);
        target.vehicle().bvpDetonateEraBrick(era.name);
        BvpLeanImpactEffects.spawnEraExplosion(target.level(), eraImpact);
        ArmorSoundService.play(target.level(), eraImpact, ArmorSoundService.PENETRATION_SOUND, 0.55F, 1.35F);

        DamageDiagnostics.event(target.vehicle(), projectile, "ERA", String.format(java.util.Locale.ROOT,
                "brick=%s type=%s pen_mm=%.0f -> %.0f tandem=%s", era.name, era.eraType, shot.penetrationMm,
                reducedShot.penetrationMm, shot.tandemWarhead));
        return new Result(true, era, eraImpact, reducedTrace, shot, reducedShot, appliedProtectionMm);
    }

    private static ArmorHit findActiveEraHit(BvpImpactVolumeQuery volumes, ShotTrace trace) {
        ArmorTarget target = volumes.target();
        ArmorProfile profile = volumes.profile();
        double impactTolerance = Math.max(profile.impactTolerance, ERA_IMPACT_TOLERANCE_MIN);
        ArmorHit eraHit = volumes.eraHit(trace, impactTolerance);
        if (eraHit == null || target.vehicle().isBvpEraBrickSpent(eraHit.plate.name)) {
            return null;
        }
        ArmorHitResolver.NearBox nearestToImpact = volumes.nearestEraToImpact(trace, impactTolerance);
        if (nearestToImpact != null
                && nearestToImpact.distance() <= SPENT_ERA_SUPPRESSION_TOLERANCE
                && target.vehicle().isBvpEraBrickSpent(nearestToImpact.box().name)) {
            return null;
        }
        return eraHit;
    }

    private static double postEraPenetration(ProjectileArmorEffect shot, double protectionMm) {
        if (shot.tandemWarhead && shot.damageType == ArmorDamageType.CHEMICAL) {
            // A tandem warhead's precursor sets the brick off; the main charge meets the armor with its full
            // penetration (owner 2026-09-28: tandem ignores ERA).
            return shot.penetrationMm;
        }
        return shot.penetrationMm - protectionMm;
    }

    private static double protectionAgainst(ArmorBox era, ProjectileArmorEffect shot) {
        if (shot.damageType != ArmorDamageType.CHEMICAL) {
            return era.kineticProtectionMm;
        }
        if (shot.atgm && "kontakt1".equals(era.eraType)) {
            return KONTAKT1_ATGM_CHEMICAL_PROTECTION_MM;
        }
        return era.chemicalProtectionMm;
    }

    record Result(boolean detonated, ArmorBox eraBox, Vec3 impactVec, ShotTrace trace,
                  ProjectileArmorEffect originalShot, ProjectileArmorEffect shot, double protectionMm) {
        static Result unchanged(ShotTrace trace, ProjectileArmorEffect shot) {
            return new Result(false, null, trace.hitVec, trace, shot, shot, 0.0D);
        }
    }
}
