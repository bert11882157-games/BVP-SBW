package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.atsuishio.superbwarfare.entity.projectile.SmallCannonShellEntity;
import com.atsuishio.superbwarfare.entity.projectile.SmallRocketEntity;
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketStandardEntity;
import com.atsuishio.superbwarfare.entity.projectile.RpgRocketTBGEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.projectile.BvpS8KoRocketEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

final class ArmorShotClassifier {
    private static final float PROJECTILE_STAT_EPSILON = 0.001F;
    // Shooter profiles eligible for the legacy 30 mm small-cannon fallback.
    private static final Set<String> SMALL_CANNON_30MM_PROFILE_IDS = Set.of("bmp2", "btr80a", "bmpt");
    private static final Set<String> HELICOPTER_ROCKET_PROFILE_IDS = Set.of(ProjectileArmorEffects.MI24V_PROFILE_ID,
            ProjectileArmorEffects.MI28N_PROFILE_ID, ProjectileArmorEffects.KA50_PROFILE_ID);
    private ArmorShotClassifier() {
    }

    static ProjectileArmorEffect classify(Projectile projectile, Entity owner, ArmorProfile targetProfile) {
        return classify(projectile, owner, targetProfile, null);
    }

    /**
     * Classifies an accepted impact using the server collision point as the only range endpoint.
     * The overload without an impact point remains the scalar/fallback compatibility path for
     * callers that do not have an accepted collision context.
     */
    static ProjectileArmorEffect classify(Projectile projectile, Entity owner,
                                          ArmorProfile targetProfile, Vec3 impactPosition) {
        ProjectileArmorEffect raw = classifyUnscaled(projectile, owner, targetProfile, impactPosition);
        // Damage normalization 2026-09-28: the profile's HullDamage is the hull damage. No mount factor and no
        // round-type balance on top; a consolidated aircraft round (one projectile standing for two) still counts
        // for the rounds it carries.
        return raw == null ? null : raw.withDirectDamageScale(
                com.atsuishio.superbwarfare.api.vehicle.weapon.AircraftRoundConsolidation.weight(projectile));
    }

    private static ProjectileArmorEffect classifyUnscaled(Projectile projectile, Entity owner,
                                          ArmorProfile targetProfile, Vec3 impactPosition) {
        ArmoredVehicleEntity shooterVehicle = shooterVehicleFor(owner, projectile);
        String shooterProfileId = shooterVehicle == null ? "" : shooterVehicle.getArmorProfileId();

        // A projectile carrying a BVP profile must resolve to a complete typed combat tuple.
        // Missing/invalid datapack metadata is a hard admission failure, never permission to
        // fall through to legacy entity/class/name damage heuristics.
        ProjectileCombatDescriptor descriptor = ProjectileProfiles.combatDescriptor(projectile);
        if (ProjectileProfiles.profileId(projectile) != null && descriptor == null) {
            return null;
        }

        ProjectileArmorEffect profiledShot = classifyProfiledShot(projectile, shooterProfileId, impactPosition);
        if (profiledShot != null
                || (descriptor != null && isBvpId(descriptor.getMunitionType(), "bullet"))) {
            return profiledShot;
        }

        if (projectile instanceof CannonShellEntity shell) {
            ProjectileArmorEffect knownShell = classifyKnownCannonShell(shell, shooterProfileId);
            if (knownShell != null) {
                return knownShell;
            }
            if (isApShell(shell)) {
                return withShell(shell, ArmorDamageType.KINETIC, penetrationFor(shooterVehicle, targetProfile),
                        shellEffect(shell, ProjectileArmorEffects.APFSDS));
            }
            if (isChemicalShell(shell)) {
                boolean atgmShell = "ATGM".equals(shellTypeName(shell));
                ProjectileArmorEffect effect = shellEffect(shell, ProjectileArmorEffects.CHEMICAL_CANNON);
                if ("HE".equals(shellTypeName(shell))) {
                    effect = effect.withVehicleDamage(
                            ProjectileArmorEffects.tankHeVehicleDamageForShooterProfile(shooterProfileId));
                }
                return withShell(shell, ArmorDamageType.CHEMICAL,
                        chemicalPenetrationFor(shooterVehicle, targetProfile),
                        effect, atgmShell);
            }
            return null;
        }
        if (HELICOPTER_ROCKET_PROFILE_IDS.contains(shooterProfileId)
                && (projectile instanceof BvpS8KoRocketEntity || projectile instanceof SmallRocketEntity)) {
            return withProjectile(ArmorDamageType.CHEMICAL, 400.0D, ProjectileArmorEffects.MI24_S8KO);
        }
        if (ProjectileArmorEffects.MI24V_PROFILE_ID.equals(shooterProfileId)
                && projectile instanceof MediumRocketEntity) {
            return withProjectile(ArmorDamageType.KINETIC, 50.0D, ProjectileArmorEffects.MI24_S13);
        }
        if (projectile instanceof MissileProjectile) {
            return atgmProjectileShot(shooterProfileId);
        }
        if (projectile instanceof SmallCannonShellEntity shell) {
            return classifySmallCannonShell(shell, shooterVehicle);
        }
        if (projectile instanceof ProjectileEntity bullet) {
            return classifyVehicleProjectile(bullet, shooterVehicle);
        }
        return null;
    }

    static ProjectileArmorEffect classifyBvpImpact(Projectile projectile, Entity owner) {
        return classifyBvpImpact(projectile, owner, null);
    }

    static ProjectileArmorEffect classifyBvpImpact(Projectile projectile, Entity owner,
                                                   Vec3 impactPosition) {
        ArmoredVehicleEntity shooterVehicle = shooterVehicleFor(owner, projectile);
        if (shooterVehicle == null && !BvpHandheldAtPolicy.owns(projectile)) {
            return null;
        }
        ProjectileArmorEffect shot = classify(projectile, owner, null, impactPosition);
        return shot == null ? visualOnlyBullet(projectile, shooterVehicle) : shot;
    }

    /** Exact rocket/missile families eligible for a bounded, accepted-point ERA activation. */
    static boolean isEraActivatingRocket(Projectile projectile, ProjectileArmorEffect shot) {
        if (projectile == null) {
            return false;
        }
        // A declared but unresolved profile must not recover through a legacy class fallback.
        ProjectileCombatDescriptor descriptor = ProjectileProfiles.combatDescriptor(projectile);
        if (ProjectileProfiles.profileId(projectile) != null && descriptor == null) {
            return false;
        }
        // TacZ RPG rounds use EntityKineticBullet even though their exact, resolved
        // BVP combat tuple is a chemical ATGM-class rocket. Admit only that typed
        // handheld round to the same local ERA radius as native rocket entities.
        boolean handheldRpg = BvpHandheldAtPolicy.owns(projectile)
                && descriptor != null
                && isBvpId(descriptor.getWeaponId(), "handheld_at")
                && isBvpId(descriptor.getRoundId(), "handheld_heat_110")
                && isBvpId(descriptor.getMunitionType(), "rocket")
                && isBvpId(descriptor.getDamageType(), "chemical")
                && descriptor.getHullDamageClass() == ProjectileHullDamageClass.ATGM;
        return (shot != null && shot.atgm) || projectile instanceof SmallRocketEntity
                || projectile instanceof MediumRocketEntity
                || projectile instanceof MissileProjectile
                || projectile instanceof RpgRocketStandardEntity
                || projectile instanceof RpgRocketTBGEntity || handheldRpg;
    }

    static ProjectileArmorEffect classifyUnmodeledBvpImpact(Projectile projectile, Entity owner) {
        return visualOnlyBullet(projectile, shooterVehicleFor(owner, projectile));
    }
    private static ProjectileArmorEffect classifyProfiledShot(Projectile projectile, String shooterProfileId,
                                                              Vec3 impactPosition) {
        ProjectileCombatDescriptor descriptor = ProjectileProfiles.combatDescriptor(projectile);
        if (descriptor == null) {
            return null;
        }

        ArmorDamageType damageType = profiledDamageType(descriptor);
        ProjectileArmorEffect template = profiledEffect(projectile, descriptor, shooterProfileId, damageType);
        if (template == null) {
            return null;
        }
        if (damageType == null) {
            damageType = template.damageType;
        }

        // Curves are sampled only from exact server launch provenance + accepted collision point.
        // If either endpoint is unavailable, ProjectileProfiles deliberately returns the scalar
        // descriptor value, preserving legacy/non-profiled behavior without consulting a shooter,
        // camera, or client interpolation state.
        Double travelRangeMetres = ProjectileProfiles.launchToImpactTravelRangeMetres(
                projectile, impactPosition);
        Double penetrationMm = ProjectileProfiles.penetrationMm(
                projectile, travelRangeMetres == null ? Double.NaN : travelRangeMetres);
        if (isBvpId(descriptor.getMunitionType(), "bullet")) {
            // The API already supplies scalar fallback for missing/malformed curves.
            // Zero is a valid sampled result, not missing metadata.
            if (penetrationMm == null || !Double.isFinite(penetrationMm) || penetrationMm < 0.0D) {
                return null;
            }
        } else {
            // Preserve the existing non-bullet scalar/typed fallback, including tank shells.
            if (penetrationMm == null || penetrationMm <= 0.0D) {
                penetrationMm = descriptor.getPenetrationMm();
            }
            if (penetrationMm == null || penetrationMm <= 0.0D) {
                penetrationMm = profiledPenetration(descriptor);
            }
            if (penetrationMm == null) {
                return null;
            }
        }

        boolean atgm = profiledAtgm(descriptor);
        ProjectileArmorEffect shot = projectile instanceof CannonShellEntity shell
                ? withShell(shell, damageType, Math.max(0.0D, penetrationMm), template, atgm)
                : withProjectile(damageType, Math.max(0.0D, penetrationMm), atgm, template);
        shot = ProjectileArmorEffects.normalizeProfiledDamage(
                projectile, shot, descriptor, shooterProfileId);
        if (shot == null) {
            return null;
        }
        return descriptor.getTandem() && !shot.tandemWarhead ? shot.withTandemWarhead() : shot;
    }

    private static ProjectileArmorEffect profiledEffect(Projectile projectile,
                                                        ProjectileCombatDescriptor descriptor,
                                                        String shooterProfileId,
                                                        ArmorDamageType damageType) {
        if (isBvpId(descriptor.getMunitionType(), "bullet")) {
            Double caliber = descriptor.getCaliberMm();
            if (caliber == null || !Double.isFinite(caliber) || caliber <= 0.0D) {
                return null;
            }
            if (Math.abs(caliber - 5.56D) <= PROJECTILE_STAT_EPSILON
                    || Math.abs(caliber - 5.8D) <= PROJECTILE_STAT_EPSILON
                    || Math.abs(caliber - 7.62D) <= PROJECTILE_STAT_EPSILON
                    || Math.abs(caliber - 7.92D) <= PROJECTILE_STAT_EPSILON
                    || Math.abs(caliber - 20.0D) <= PROJECTILE_STAT_EPSILON) {
                return profiledLightGunBulletEffect(projectile, descriptor, damageType);
            }
        }
        String roundId = normalizedId(descriptor.getRoundId());
        if (BvpHandheldAtPolicy.owns(projectile)
                && isBvpId(descriptor.getMunitionType(), "rocket")
                && damageType == ArmorDamageType.CHEMICAL) {
            return ProjectileArmorEffects.HEAT_FS;
        }
        ProjectileArmorEffect known = switch (roundId) {
            case "3bm42", "round_3bm42" -> ProjectileArmorEffects.ROUND_3BM42;
            case "3bm60", "round_3bm60" -> ProjectileArmorEffects.ROUND_3BM60;
            case "3bk18m_heat_fs", "3bk18m_heatfs", "round_3bk18m_heat_fs" ->
                    ProjectileArmorEffects.ROUND_3BK18M_HEAT_FS;
            case "3of26_he", "round_3of26_he" -> ProjectileArmorEffects.ROUND_3OF26_HE;
            case "9m113_konkurs", "atgm_9m113_konkurs" -> ProjectileArmorEffects.ATGM_9M113_KONKURS;
            case "9m119m1_tandem", "atgm_9m119m1_tandem" ->
                    ProjectileArmorEffects.ATGM_9M119M1_TANDEM;
            case "9m117_bastion", "atgm_9m117_bastion" -> ProjectileArmorEffects.ATGM_9M117_BASTION;
            case "9m114_shturm", "atgm_9m114_shturm" -> ProjectileArmorEffects.ATGM_9M114_SHTURM;
            case "9m120_ataka", "atgm_9m120_ataka" -> ProjectileArmorEffects.ATGM_9M120_ATAKA;
            case "9k127_vikhr", "atgm_9k127_vikhr" -> ProjectileArmorEffects.ATGM_9K127_VIKHR;
            case "20mm_apds" -> ProjectileArmorEffects.AUTOCANNON_20MM_APDS;
            case "20mm_he" -> ProjectileArmorEffects.AUTOCANNON_20MM_HE;
            case "23mm_apds" -> ProjectileArmorEffects.AUTOCANNON_23MM_APDS;
            case "23mm_he" -> ProjectileArmorEffects.AUTOCANNON_23MM_HE;
            case "30mm_apds", "3ubr8_apds" -> ProjectileArmorEffects.AUTOCANNON_30MM_APDS;
            case "30mm_he" -> ProjectileArmorEffects.AUTOCANNON_30MM_HE;
            case "coax_762", "7_62_coax", "pkt" -> ProjectileArmorEffects.COAX_762;
            case "hmg_50", "50_cal", "yakb" -> ProjectileArmorEffects.HMG_FIFTY_CAL;
            case "s8ko", "s_8ko" -> ProjectileArmorEffects.MI24_S8KO;
            case "s13", "s_13" -> ProjectileArmorEffects.MI24_S13;
            default -> null;
        };
        if (known != null) {
            return known;
        }

        if (profiledAtgm(descriptor)) {
            int durabilityHint = descriptor.getTandem()
                    ? (int) ProjectileArmorEffects.TANDEM_ATGM_PENETRATION_MM
                    : 0;
            return AtgmArmorProfiles.forShot(shooterProfileId, durabilityHint).effect();
        }

        // Caliber is a typed Combat field. Resolve the complete 12.7/14.5 family before any
        // legacy weapon-path fallback so every generated belt component receives its normalized
        // BVP armor effect, including passenger-station profiles.
        if (descriptor.getCaliberMm() != null
                && Math.abs(descriptor.getCaliberMm() - 12.7D) <= PROJECTILE_STAT_EPSILON) {
            return ProjectileArmorEffects.HMG_FIFTY_CAL;
        }
        if (descriptor.getCaliberMm() != null
                && Math.abs(descriptor.getCaliberMm() - 14.5D) <= PROJECTILE_STAT_EPSILON) {
            return ProjectileArmorEffects.KPVT_14_5;
        }

        String weaponId = normalizedId(descriptor.getWeaponId());
        if (containsAny(weaponId, "coax", "pkt")) {
            return ProjectileArmorEffects.COAX_762;
        }
        if (containsAny(weaponId, "hmg", "yakb")) {
            return ProjectileArmorEffects.HMG_FIFTY_CAL;
        }

        ProjectileArmorEffect autocannon = profiledAutocannonEffect(projectile, descriptor, damageType);
        if (autocannon != null) {
            return autocannon;
        }

        if (projectile instanceof CannonShellEntity) {
            return damageType == ArmorDamageType.KINETIC
                    ? ProjectileArmorEffects.APFSDS
                    : damageType == ArmorDamageType.CHEMICAL
                    ? ProjectileArmorEffects.CHEMICAL_CANNON
                    : null;
        }
        return null;
    }

    private static ProjectileArmorEffect profiledLightGunBulletEffect(
            Projectile projectile,
            ProjectileCombatDescriptor descriptor,
            ArmorDamageType damageType) {
        if (!(projectile instanceof ProjectileEntity)
                || !descriptor.hasPrecomputedDamage()
                || !isBvpId(descriptor.getMunitionType(), "bullet")
                || !isBvpId(descriptor.getRoundId(), null)
                || !isBvpId(descriptor.getDamageType(), null)) {
            return null;
        }

        Double caliber = descriptor.getCaliberMm();
        if (caliber == null || !Double.isFinite(caliber)) {
            return null;
        }
        boolean smallArms = Math.abs(caliber - 5.56D) <= PROJECTILE_STAT_EPSILON
                || Math.abs(caliber - 5.8D) <= PROJECTILE_STAT_EPSILON
                || Math.abs(caliber - 7.62D) <= PROJECTILE_STAT_EPSILON
                || Math.abs(caliber - 7.92D) <= PROJECTILE_STAT_EPSILON;
        boolean twenty = Math.abs(caliber - 20.0D) <= PROJECTILE_STAT_EPSILON;
        boolean kinetic = (smallArms || twenty)
                && damageType == ArmorDamageType.KINETIC
                && descriptor.getHullDamageClass() == ProjectileHullDamageClass.DEFAULT;
        boolean chemical = twenty
                && damageType == ArmorDamageType.CHEMICAL
                && descriptor.getHullDamageClass() == ProjectileHullDamageClass.HE;

        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(projectile);
        if (profile == null || !isBvpId(profile.getId(), null)
                || !isBvpId(profile.getImpactVisualProfileId(), null)) {
            return null;
        }

        // Templates supply presentation only. classifyProfiledShot subsequently
        // applies the exact descriptor's damage type, curve and complete damage tuple.
        return switch (normalizedId(profile.getImpactVisualProfileId())) {
            case "autocannon_ap", "ap", "apds" -> kinetic
                    ? ProjectileArmorEffects.AUTOCANNON_30MM_APDS : null;
            case "bullet" -> smallArms && kinetic
                    ? ProjectileArmorEffects.COAX_762 : null;
            case "autocannon_he", "he" -> chemical
                    ? ProjectileArmorEffects.AUTOCANNON_30MM_HE : null;
            default -> null;
        };
    }

    /**
     * Admits every BVP-authored autocannon caliber through its immutable projectile profile.
     * The returned 30 mm effect is only a presentation template: {@code classifyProfiledShot}
     * replaces penetration and all damage values from the resolved Combat descriptor before the
     * impact is applied.  This avoids another caliber whitelist while retaining one established
     * AP/HE visual and shrapnel policy for 20, 23, 25, 30, 40 mm and future typed autocannons.
     */
    private static ProjectileArmorEffect profiledAutocannonEffect(Projectile projectile,
                                                                  ProjectileCombatDescriptor descriptor,
                                                                  ArmorDamageType damageType) {
        if (projectile == null || descriptor == null || damageType == null
                || !isBvpId(descriptor.getMunitionType(), "autocannon_shell")) {
            return null;
        }

        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(projectile);
        if (profile == null || !isBvpId(profile.getId(), null)) {
            return null;
        }
        ResourceLocation impactProfileId = profile.getImpactVisualProfileId();
        if (!isBvpId(impactProfileId, null)) {
            return null;
        }

        return switch (normalizedId(impactProfileId)) {
            case "autocannon_ap", "ap", "apds" -> damageType == ArmorDamageType.KINETIC
                    ? ProjectileArmorEffects.AUTOCANNON_30MM_APDS
                    : null;
            // APHE is kinetic; only its filled-round presentation reuses the HE template.
            // withProjectile preserves damageType, then normalization supplies descriptor damage.
            case "autocannon_he", "he" -> (damageType == ArmorDamageType.CHEMICAL
                    || (damageType == ArmorDamageType.KINETIC
                        && descriptor.getHullDamageClass() == ProjectileHullDamageClass.APHE))
                    ? ProjectileArmorEffects.AUTOCANNON_30MM_HE
                    : null;
            default -> null;
        };
    }

    private static ArmorDamageType profiledDamageType(ProjectileCombatDescriptor descriptor) {
        return switch (normalizedId(descriptor.getDamageType())) {
            case "kinetic", "ap", "apds", "apfsds" -> ArmorDamageType.KINETIC;
            case "chemical", "heat", "heat_fs", "heatfs", "he", "atgm" -> ArmorDamageType.CHEMICAL;
            default -> null;
        };
    }

    private static boolean isBvpId(ResourceLocation id, String expectedPath) {
        return id != null
                && BertsVehiclePack.MODID.equals(id.m_135827_())
                && (expectedPath == null || expectedPath.equals(normalizedId(id)));
    }

    private static Double profiledPenetration(ProjectileCombatDescriptor descriptor) {
        return switch (normalizedId(descriptor.getRoundId())) {
            case "3bm42", "round_3bm42" -> ProjectileArmorEffects.ROUND_3BM42_PENETRATION_MM;
            case "3bm60", "round_3bm60" -> ProjectileArmorEffects.ROUND_3BM60_PENETRATION_MM;
            case "3bk18m_heat_fs", "3bk18m_heatfs", "round_3bk18m_heat_fs" ->
                    ProjectileArmorEffects.ROUND_3BK18M_HEAT_FS_PENETRATION_MM;
            case "3of26_he", "round_3of26_he" -> ProjectileArmorEffects.ROUND_3OF26_HE_PENETRATION_MM;
            case "9m119m1_tandem", "atgm_9m119m1_tandem", "9m120_ataka", "atgm_9m120_ataka",
                    "9k127_vikhr", "atgm_9k127_vikhr" -> ProjectileArmorEffects.TANDEM_ATGM_PENETRATION_MM;
            case "9m113_konkurs", "atgm_9m113_konkurs", "9m117_bastion", "atgm_9m117_bastion",
                    "9m114_shturm", "atgm_9m114_shturm" ->
                    ProjectileArmorEffects.NON_TANDEM_ATGM_PENETRATION_MM;
            case "20mm_apds" -> ProjectileArmorEffects.AUTOCANNON_20MM_APDS_PENETRATION_MM;
            case "20mm_he" -> ProjectileArmorEffects.AUTOCANNON_20MM_HE_PENETRATION_MM;
            case "23mm_apds" -> ProjectileArmorEffects.AUTOCANNON_23MM_APDS_PENETRATION_MM;
            case "23mm_he" -> ProjectileArmorEffects.AUTOCANNON_23MM_HE_PENETRATION_MM;
            case "30mm_apds", "3ubr8_apds" -> ProjectileArmorEffects.AUTOCANNON_30MM_APDS_PENETRATION_MM;
            case "30mm_he" -> ProjectileArmorEffects.AUTOCANNON_30MM_HE_PENETRATION_MM;
            case "coax_762", "7_62_coax", "pkt" -> ProjectileArmorEffects.COAX_762_PENETRATION_MM;
            case "s8ko", "s_8ko" -> 400.0D;
            case "s13", "s_13" -> 50.0D;
            default -> null;
        };
    }

    private static boolean profiledAtgm(ProjectileCombatDescriptor descriptor) {
        String munitionType = normalizedId(descriptor.getMunitionType());
        String roundId = normalizedId(descriptor.getRoundId());
        String weaponId = normalizedId(descriptor.getWeaponId());
        return containsAny(munitionType, "atgm", "missile")
                || containsAny(roundId, "atgm", "missile")
                || roundId.startsWith("9m")
                || roundId.startsWith("9k")
                || containsAny(weaponId, "atgm", "missile");
    }

    private static String normalizedId(ResourceLocation id) {
        return id == null ? "" : id.m_135815_().trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static ProjectileArmorEffect classifyKnownCannonShell(CannonShellEntity shell, String shooterProfileId) {
        if (is3bm60(shell)) {
            return withShell(shell, ArmorDamageType.KINETIC,
                    ProjectileArmorEffects.ROUND_3BM60_PENETRATION_MM,
                    ProjectileArmorEffects.ROUND_3BM60);
        }
        if (is3bm42(shell)) {
            return withShell(shell, ArmorDamageType.KINETIC,
                    ProjectileArmorEffects.ROUND_3BM42_PENETRATION_MM,
                    ProjectileArmorEffects.ROUND_3BM42);
        }
        if (is3bk18mHeatFs(shell)) {
            return withShell(shell, ArmorDamageType.CHEMICAL,
                    ProjectileArmorEffects.ROUND_3BK18M_HEAT_FS_PENETRATION_MM,
                    ProjectileArmorEffects.ROUND_3BK18M_HEAT_FS);
        }
        if (is3of26He(shell)) {
            return withShell(shell, ArmorDamageType.CHEMICAL,
                    ProjectileArmorEffects.ROUND_3OF26_HE_PENETRATION_MM,
                    ProjectileArmorEffects.ROUND_3OF26_HE.withVehicleDamage(
                            ProjectileArmorEffects.tankHeVehicleDamageForShooterProfile(shooterProfileId)));
        }
        if ("ATGM".equals(shellTypeName(shell))) {
            return atgmShellShot(shell, shooterProfileId);
        }
        return null;
    }

    private static ProjectileArmorEffect atgmShellShot(CannonShellEntity shell, String shooterProfileId) {
        int durability = shell.getDurability();
        AtgmArmorProfiles.Profile atgm = AtgmArmorProfiles.forShot(shooterProfileId, durability);
        return withShell(shell, ArmorDamageType.CHEMICAL,
                atgm.penetrationMm(), atgm.effect(), true);
    }

    private static ProjectileArmorEffect atgmProjectileShot(String shooterProfileId) {
        AtgmArmorProfiles.Profile atgm = AtgmArmorProfiles.forShot(shooterProfileId, 0);
        return withProjectile(ArmorDamageType.CHEMICAL, atgm.penetrationMm(), true, atgm.effect());
    }

    private static ProjectileArmorEffect classifySmallCannonShell(SmallCannonShellEntity shell,
                                                                  ArmoredVehicleEntity shooterVehicle) {
        int caliber = smallCannonCaliberFor(shooterVehicle);
        if (caliber == 0) {
            return null;
        }
        return isAutocannonHeShell(shell, caliber)
                ? autocannonHeShot(caliber)
                : autocannonApdsShot(caliber);
    }

    private static ProjectileArmorEffect classifyVehicleProjectile(ProjectileEntity bullet,
                                                                  ArmoredVehicleEntity shooterVehicle) {
        if (isHelicopter2a42Autocannon30MmShooter(shooterVehicle)) {
            if (nearlyEquals(bullet.getDamage(), ProjectileArmorEffects.AUTOCANNON_HE_PROJECTILE_DAMAGE)) {
                return autocannonHeShot(30);
            }
            if (bullet.getDamage() >= ProjectileArmorEffects.AUTOCANNON_30MM_PROJECTILE_MIN_DAMAGE) {
                return autocannonApdsShot(30);
            }
        }
        if (isVehicleCoax762Projectile(bullet, shooterVehicle)) {
            return withProjectile(ArmorDamageType.KINETIC, ProjectileArmorEffects.COAX_762_PENETRATION_MM,
                    ProjectileArmorEffects.COAX_762);
        }
        return classifyVehicleHmgBullet(bullet, shooterVehicle);
    }

    private static boolean isHelicopter2a42Autocannon30MmShooter(ArmoredVehicleEntity shooterVehicle) {
        return shooterVehicle != null
                && (ProjectileArmorEffects.MI28N_PROFILE_ID.equals(shooterVehicle.getArmorProfileId())
                || ProjectileArmorEffects.KA50_PROFILE_ID.equals(shooterVehicle.getArmorProfileId()));
    }

    private static ProjectileArmorEffect autocannonApdsShot(int caliber) {
        return switch (caliber) {
            case 20 -> withProjectile(ArmorDamageType.KINETIC,
                    ProjectileArmorEffects.AUTOCANNON_20MM_APDS_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_20MM_APDS);
            case 23 -> withProjectile(ArmorDamageType.KINETIC,
                    ProjectileArmorEffects.AUTOCANNON_23MM_APDS_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_23MM_APDS);
            case 30 -> withProjectile(ArmorDamageType.KINETIC,
                    ProjectileArmorEffects.AUTOCANNON_30MM_APDS_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_30MM_APDS);
            default -> null;
        };
    }

    private static ProjectileArmorEffect autocannonHeShot(int caliber) {
        return switch (caliber) {
            case 20 -> withProjectile(ArmorDamageType.CHEMICAL,
                    ProjectileArmorEffects.AUTOCANNON_20MM_HE_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_20MM_HE);
            case 23 -> withProjectile(ArmorDamageType.CHEMICAL,
                    ProjectileArmorEffects.AUTOCANNON_23MM_HE_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_23MM_HE);
            case 30 -> withProjectile(ArmorDamageType.CHEMICAL,
                    ProjectileArmorEffects.AUTOCANNON_30MM_HE_PENETRATION_MM,
                    ProjectileArmorEffects.AUTOCANNON_30MM_HE);
            default -> null;
        };
    }

    private static boolean isAutocannonHeShell(SmallCannonShellEntity shell, int caliber) {
        float expectedDamage = caliber == 30
                ? ProjectileArmorEffects.AUTOCANNON_HE_PROJECTILE_DAMAGE
                : ProjectileArmorEffects.AUTOCANNON_LEGACY_HE_PROJECTILE_DAMAGE;
        float expectedExplosionDamage = caliber == 30
                ? ProjectileArmorEffects.AUTOCANNON_HE_PROJECTILE_EXPLOSION_DAMAGE
                : ProjectileArmorEffects.AUTOCANNON_LEGACY_HE_PROJECTILE_EXPLOSION_DAMAGE;
        return nearlyEquals(shell.getDamageValue(), expectedDamage)
                && nearlyEquals(shell.getExplosionDamageValue(), expectedExplosionDamage);
    }

    private static int smallCannonCaliberFor(ArmoredVehicleEntity shooterVehicle) {
        if (shooterVehicle == null) {
            return 0;
        }
        String profileId = shooterVehicle.getArmorProfileId();
        if (SMALL_CANNON_30MM_PROFILE_IDS.contains(profileId)) {
            return 30;
        }
        double apPenetrationMm = shooterVehicle.getApPenetrationMm();
        if (nearlyEquals(apPenetrationMm, ProjectileArmorEffects.AUTOCANNON_20MM_APDS_PENETRATION_MM)) {
            return 20;
        }
        if (nearlyEquals(apPenetrationMm, ProjectileArmorEffects.AUTOCANNON_23MM_APDS_PENETRATION_MM)) {
            return 23;
        }
        if (nearlyEquals(apPenetrationMm, ProjectileArmorEffects.AUTOCANNON_30MM_APDS_PENETRATION_MM)) {
            return 30;
        }
        return 0;
    }

    private static boolean isVehicleCoax762Projectile(ProjectileEntity bullet,
                                                      ArmoredVehicleEntity shooterVehicle) {
        // SBW projectile hit events do not expose weapon IDs, so match the generated coax stats.
        return shooterVehicle != null
                && nearlyEquals(bullet.getDamage(), ProjectileArmorEffects.COAX_762_PROJECTILE_DAMAGE)
                && nearlyEquals(bullet.getBypassArmorRate(),
                        ProjectileArmorEffects.COAX_762_PROJECTILE_BYPASS_ARMOR_RATE);
    }

    private static ProjectileArmorEffect classifyVehicleHmgBullet(ProjectileEntity bullet,
                                                                  ArmoredVehicleEntity shooterVehicle) {
        if (shooterVehicle == null || !isVehicleHmgProjectile(bullet, shooterVehicle.getArmorProfileId())) {
            return null;
        }
        Double penetrationMm = ProjectileArmorEffects.hmgPenetrationMm(shooterVehicle.getArmorProfileId());
        if (penetrationMm == null) {
            return null;
        }
        return withProjectile(ArmorDamageType.KINETIC, penetrationMm, ProjectileArmorEffects.HMG_FIFTY_CAL);
    }

    private static ProjectileArmorEffect visualOnlyBullet(Projectile projectile,
                                                          ArmoredVehicleEntity shooterVehicle) {
        if (!(projectile instanceof ProjectileEntity bullet) || shooterVehicle == null) {
            return null;
        }
        ProjectileArmorEffect effect = isVehicleHmgProjectile(bullet, shooterVehicle.getArmorProfileId())
                ? ProjectileArmorEffects.HMG_IMPACT
                : ProjectileArmorEffects.GENERIC_BULLET_IMPACT;
        return withProjectile(ArmorDamageType.KINETIC, 0.0D, effect);
    }

    private static boolean isVehicleHmgProjectile(ProjectileEntity bullet, String shooterProfileId) {
        return ProjectileArmorEffects.MI24V_PROFILE_ID.equals(shooterProfileId)
                ? bullet.getDamage() >= ProjectileArmorEffects.YAKB_PROJECTILE_MIN_DAMAGE
                : bullet.getDamage() >= ProjectileArmorEffects.HMG_PROJECTILE_MIN_DAMAGE
                && bullet.getBypassArmorRate() >= ProjectileArmorEffects.HMG_PROJECTILE_MIN_BYPASS_ARMOR_RATE;
    }

    private static boolean nearlyEquals(double left, double right) {
        return Math.abs(left - right) <= PROJECTILE_STAT_EPSILON;
    }

    private static double penetrationFor(ArmoredVehicleEntity shooterVehicle, ArmorProfile targetProfile) {
        return shooterVehicle != null ? shooterVehicle.getApPenetrationMm()
                : targetProfile.fallbackIncomingPenetrationMm;
    }

    private static double chemicalPenetrationFor(ArmoredVehicleEntity shooterVehicle, ArmorProfile targetProfile) {
        return shooterVehicle != null ? ArmorProfiles.get(shooterVehicle.getArmorProfileId()).chemicalPenetrationMm
                : targetProfile.chemicalPenetrationMm;
    }

    private static boolean is3bm42(CannonShellEntity shell) {
        return "AP".equals(shellTypeName(shell))
                && shell.getDurability() == (int) ProjectileArmorEffects.ROUND_3BM42_PENETRATION_MM;
    }

    private static boolean is3bm60(CannonShellEntity shell) {
        return "AP".equals(shellTypeName(shell))
                && shell.getDurability() == (int) ProjectileArmorEffects.ROUND_3BM60_PENETRATION_MM;
    }

    private static boolean is3bk18mHeatFs(CannonShellEntity shell) {
        String shellType = shellTypeName(shell);
        return ("HE".equals(shellType) || "CM".equals(shellType))
                && shell.getDurability() == (int) ProjectileArmorEffects.ROUND_3BK18M_HEAT_FS_PENETRATION_MM;
    }

    private static boolean is3of26He(CannonShellEntity shell) {
        return "HE".equals(shellTypeName(shell))
                && shell.getDurability() == (int) ProjectileArmorEffects.ROUND_3OF26_HE_PENETRATION_MM;
    }

    private static ProjectileArmorEffect withShell(CannonShellEntity shell, ArmorDamageType type,
                                                   double penetrationMm, ProjectileArmorEffect effect) {
        return withShell(shell, type, penetrationMm, effect, false);
    }

    private static ProjectileArmorEffect withShell(CannonShellEntity shell, ArmorDamageType type,
                                                   double penetrationMm, ProjectileArmorEffect effect,
                                                   boolean atgm) {
        ProjectileArmorEffect shot = new ProjectileArmorEffect(type, penetrationMm, shell, atgm,
                effect.moduleDamage(), effect.moduleDamageOverrides(), effect.vehicleDamage)
                .withImpactVisual(effect.impactVisual);
        return effect.tandemWarhead ? shot.withTandemWarhead() : shot;
    }

    private static ProjectileArmorEffect withProjectile(ArmorDamageType type, double penetrationMm,
                                                       ProjectileArmorEffect effect) {
        return withProjectile(type, penetrationMm, false, effect);
    }

    private static ProjectileArmorEffect withProjectile(ArmorDamageType type, double penetrationMm,
                                                       boolean atgm, ProjectileArmorEffect effect) {
        ProjectileArmorEffect shot = new ProjectileArmorEffect(type, penetrationMm, null, atgm,
                effect.moduleDamage(), effect.moduleDamageOverrides(), effect.vehicleDamage)
                .withImpactVisual(effect.impactVisual);
        return effect.tandemWarhead ? shot.withTandemWarhead() : shot;
    }

    private static ProjectileArmorEffect shellEffect(CannonShellEntity shell, ProjectileArmorEffect fallback) {
        return ProjectileArmorEffects.cannonShellEffect(shellTypeName(shell), fallback);
    }

    private static boolean isApShell(CannonShellEntity shell) {
        String shellType = shellTypeName(shell);
        if (!shellType.isEmpty()) {
            return "AP".equals(shellType);
        }
        return shell.getExplosionDamageValue() <= 90.0F;
    }

    private static boolean isChemicalShell(CannonShellEntity shell) {
        return switch (shellTypeName(shell)) {
            case "ATGM", "CM", "HEAT", "HEAT_FS", "HEATFS" -> true;
            default -> false;
        };
    }

    private static String shellTypeName(CannonShellEntity shell) {
        CannonShellEntity.Type type = shell == null ? null : shell.getShellType();
        return type == null ? "" : type.name();
    }

    private static ArmoredVehicleEntity shooterVehicleFor(Entity owner, Projectile projectile) {
        ArmoredVehicleEntity shooterVehicle = ArmorTargetAdapters.vehicleFor(owner);
        if (shooterVehicle != null) {
            return shooterVehicle;
        }
        if (projectile instanceof ProjectileEntity bullet) {
            shooterVehicle = ArmorTargetAdapters.vehicleFor(bullet.getShooter());
            if (shooterVehicle != null) {
                return shooterVehicle;
            }
        }
        return ArmorTargetAdapters.vehicleFor(projectile.m_19749_());
    }
}
