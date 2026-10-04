package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import java.util.Map;
import java.util.Locale;
import net.minecraft.world.entity.projectile.Projectile;

final class ProjectileArmorEffects {
    static final String MI24V_PROFILE_ID = "mi24v";
    static final String MI28N_PROFILE_ID = "mi28n";
    static final String KA50_PROFILE_ID = "ka50";
    static final String BMP2_PROFILE_ID = "bmp2";
    static final String T90A_PROFILE_ID = "t90a";
    static final String BMPT_PROFILE_ID = "bmpt";
    static final String T62M1_PROFILE_ID = "t62m1";
    static final double TANK_SHELL_MODULE_DAMAGE = 40.0D;
    static final double NON_TANDEM_ATGM_MODULE_DAMAGE = 75.0D;
    static final double TANDEM_ATGM_MODULE_DAMAGE = 100.0D;
    static final double TANK_SHELL_VEHICLE_DAMAGE = 175.0D;
    static final double ROUND_3BM42_PENETRATION_MM = 457.0D;
    static final double ROUND_3BM42_VEHICLE_DAMAGE = 250.0D;
    static final double ROUND_3BM60_PENETRATION_MM = 580.0D;
    static final double ROUND_3BM60_VEHICLE_DAMAGE = 280.0D;
    static final double ROUND_3BK18M_HEAT_FS_PENETRATION_MM = 550.0D;
    static final double ROUND_3BK18M_VEHICLE_DAMAGE = 140.0D;
    static final double ROUND_3OF26_HE_PENETRATION_MM = 50.0D;
    /** Generic tank-HE basis; typed weapons with a known APFSDS basis override this per weapon. */
    static final double TANK_HE_DEFAULT_VEHICLE_DAMAGE = TANK_SHELL_VEHICLE_DAMAGE * 2.0D;
    static final double ROUND_3OF26_VEHICLE_DAMAGE = TANK_HE_DEFAULT_VEHICLE_DAMAGE;
    static final double NON_TANDEM_ATGM_PENETRATION_MM = 575.0D;
    static final double TANDEM_ATGM_PENETRATION_MM = 800.0D;
    /** Unprofiled (SBW native) guided missiles: the usual ATGM, 40 % of an MBT (damage normalization 2026-09-28). */
    static final double DEFAULT_ATGM_VEHICLE_DAMAGE = 120.0D;
    static final double ATGM_9M113_KONKURS_VEHICLE_DAMAGE = 180.0D;
    static final double ATGM_9M119M1_TANDEM_VEHICLE_DAMAGE = 225.0D;
    static final double ATGM_9M117_BASTION_VEHICLE_DAMAGE = 200.0D;
    static final double ATGM_9M114_SHTURM_VEHICLE_DAMAGE = 210.0D;
    static final double ATGM_9M120_ATAKA_VEHICLE_DAMAGE = 225.0D;
    static final double ATGM_9K127_VIKHR_VEHICLE_DAMAGE = 205.0D;
    static final float HMG_PROJECTILE_MIN_DAMAGE = 30.0F;
    static final float YAKB_PROJECTILE_MIN_DAMAGE = 20.0F;
    static final float AUTOCANNON_30MM_PROJECTILE_MIN_DAMAGE = 26.0F;
    static final float AUTOCANNON_LEGACY_HE_PROJECTILE_DAMAGE = 15.0F;
    static final float AUTOCANNON_LEGACY_HE_PROJECTILE_EXPLOSION_DAMAGE = 18.0F;
    static final float AUTOCANNON_HE_PROJECTILE_DAMAGE = 250.0F;
    static final float AUTOCANNON_HE_PROJECTILE_EXPLOSION_DAMAGE = 0.0F;
    static final float HMG_PROJECTILE_MIN_BYPASS_ARMOR_RATE = 0.55F;
    static final float COAX_762_PROJECTILE_DAMAGE = 9.5F;
    static final float COAX_762_PROJECTILE_BYPASS_ARMOR_RATE = 0.3F;
    static final double AUTOCANNON_20MM_APDS_PENETRATION_MM = 38.0D;
    static final double AUTOCANNON_23MM_APDS_PENETRATION_MM = 50.0D;
    static final double AUTOCANNON_30MM_APDS_PENETRATION_MM = 85.0D;
    static final double AUTOCANNON_20MM_HE_PENETRATION_MM = 13.0D;
    static final double AUTOCANNON_23MM_HE_PENETRATION_MM = 15.0D;
    static final double AUTOCANNON_30MM_HE_PENETRATION_MM = 20.0D;
    // Vehicle-only balance basis. Player/block projectile damage remains authored separately.
    static final double AUTOCANNON_30MM_APDS_VEHICLE_DAMAGE = 25.0D;
    static final double AUTOCANNON_30MM_HE_VEHICLE_DAMAGE =
            AUTOCANNON_30MM_APDS_VEHICLE_DAMAGE * 1.4D;
    static final double NORMALIZED_50_CAL_HULL_DAMAGE = 15.0D;
    static final double NORMALIZED_50_CAL_MODULE_DAMAGE = 2.0D;
    static final double NORMALIZED_50_CAL_AMMO_RACK_DAMAGE = 1.0D;
    static final double NORMALIZED_KPVT_HULL_DAMAGE = NORMALIZED_50_CAL_HULL_DAMAGE * 1.25D;
    static final double NORMALIZED_KPVT_MODULE_DAMAGE = NORMALIZED_50_CAL_MODULE_DAMAGE * 1.25D;
    static final double NORMALIZED_KPVT_AMMO_RACK_DAMAGE = NORMALIZED_50_CAL_AMMO_RACK_DAMAGE * 1.25D;
    static final double NORMALIZED_30MM_APDS_HULL_DAMAGE = 60.0D;
    static final double NORMALIZED_30MM_HE_HULL_DAMAGE = 120.0D;
    static final double COAX_762_PENETRATION_MM = 8.0D;
    static final double COAX_762_MODULE_DAMAGE = 1.0D;
    static final double COAX_762_VEHICLE_DAMAGE = 1.0D;

    static final ProjectileArmorEffect ROUND_3BM42 = effect(
            ArmorDamageType.KINETIC,
            TANK_SHELL_MODULE_DAMAGE,
            ROUND_3BM42_VEHICLE_DAMAGE,
            5.0D
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.APFSDS);
    static final ProjectileArmorEffect ROUND_3BM60 = effect(
            ArmorDamageType.KINETIC,
            TANK_SHELL_MODULE_DAMAGE,
            ROUND_3BM60_VEHICLE_DAMAGE,
            5.0D
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.APFSDS);
    static final ProjectileArmorEffect ROUND_3BK18M_HEAT_FS = effect(
            ArmorDamageType.CHEMICAL,
            TANK_SHELL_MODULE_DAMAGE,
            ROUND_3BK18M_VEHICLE_DAMAGE,
            7.0D
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.HEAT_FS);
    static final ProjectileArmorEffect ROUND_3OF26_HE = effect(
            ArmorDamageType.CHEMICAL,
            TANK_SHELL_MODULE_DAMAGE,
            ROUND_3OF26_VEHICLE_DAMAGE,
            7.0D
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.HE);
    static final ProjectileArmorEffect DEFAULT_ATGM = nonTandemAtgm(DEFAULT_ATGM_VEHICLE_DAMAGE);
    static final ProjectileArmorEffect ATGM_9M113_KONKURS = nonTandemAtgm(ATGM_9M113_KONKURS_VEHICLE_DAMAGE);
    static final ProjectileArmorEffect ATGM_9M119M1_TANDEM = tandemAtgm(ATGM_9M119M1_TANDEM_VEHICLE_DAMAGE, 7.0D);
    static final ProjectileArmorEffect ATGM_9M117_BASTION = nonTandemAtgm(ATGM_9M117_BASTION_VEHICLE_DAMAGE);
    static final ProjectileArmorEffect ATGM_9M114_SHTURM = nonTandemAtgm(ATGM_9M114_SHTURM_VEHICLE_DAMAGE);
    static final ProjectileArmorEffect ATGM_9M120_ATAKA = tandemAtgm(ATGM_9M120_ATAKA_VEHICLE_DAMAGE, 8.0D);
    static final ProjectileArmorEffect ATGM_9K127_VIKHR = tandemAtgm(ATGM_9K127_VIKHR_VEHICLE_DAMAGE, 8.0D);
    static final ProjectileArmorEffect APFSDS = effect(
            ArmorDamageType.KINETIC,
            TANK_SHELL_MODULE_DAMAGE,
            TANK_SHELL_VEHICLE_DAMAGE,
            5.0D
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.APFSDS);
    static final ProjectileArmorEffect CHEMICAL_CANNON = effect(
            ArmorDamageType.CHEMICAL,
            TANK_SHELL_MODULE_DAMAGE,
            TANK_SHELL_VEHICLE_DAMAGE
    );
    static final ProjectileArmorEffect HEAT_FS = effect(
            ArmorDamageType.CHEMICAL,
            TANK_SHELL_MODULE_DAMAGE,
            TANK_SHELL_VEHICLE_DAMAGE
    ).withImpactVisual(ProjectileArmorEffect.ImpactVisual.HEAT_FS);
    static final ProjectileArmorEffect HMG_FIFTY_CAL = effect(ArmorDamageType.KINETIC,
            NORMALIZED_50_CAL_MODULE_DAMAGE, NORMALIZED_50_CAL_HULL_DAMAGE,
            NORMALIZED_50_CAL_AMMO_RACK_DAMAGE)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.HMG);
    static final ProjectileArmorEffect KPVT_14_5 = effect(ArmorDamageType.KINETIC,
            NORMALIZED_KPVT_MODULE_DAMAGE, NORMALIZED_KPVT_HULL_DAMAGE,
            NORMALIZED_KPVT_AMMO_RACK_DAMAGE)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_AP);
    static final ProjectileArmorEffect AUTOCANNON_20MM_APDS = effect(ArmorDamageType.KINETIC, 2.5D, 20.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_AP);
    static final ProjectileArmorEffect AUTOCANNON_23MM_APDS = effect(ArmorDamageType.KINETIC, 2.8D, 23.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_AP);
    static final ProjectileArmorEffect AUTOCANNON_30MM_APDS = effect(ArmorDamageType.KINETIC, 2.0D,
            AUTOCANNON_30MM_APDS_VEHICLE_DAMAGE)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_AP);
    static final ProjectileArmorEffect AUTOCANNON_20MM_HE = effect(ArmorDamageType.CHEMICAL, 4.0D, 68.0D, 5.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_HE);
    static final ProjectileArmorEffect AUTOCANNON_23MM_HE = effect(ArmorDamageType.CHEMICAL, 4.0D, 75.0D, 5.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_HE);
    static final ProjectileArmorEffect AUTOCANNON_30MM_HE = effect(ArmorDamageType.CHEMICAL, 5.0D,
            AUTOCANNON_30MM_HE_VEHICLE_DAMAGE, 5.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.AUTOCANNON_HE);
    static final ProjectileArmorEffect COAX_762 = effect(ArmorDamageType.KINETIC,
            COAX_762_MODULE_DAMAGE, COAX_762_VEHICLE_DAMAGE)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.BULLET);
    static final ProjectileArmorEffect GENERIC_BULLET_IMPACT = effect(ArmorDamageType.KINETIC, 0.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.BULLET);
    static final ProjectileArmorEffect HMG_IMPACT = effect(ArmorDamageType.KINETIC, 0.0D)
            .withImpactVisual(ProjectileArmorEffect.ImpactVisual.HMG);
    static final ProjectileArmorEffect MI24_S13 = effect(ArmorDamageType.KINETIC, TANK_SHELL_MODULE_DAMAGE,
            TANK_SHELL_VEHICLE_DAMAGE);
    static final ProjectileArmorEffect MI24_S8KO = effect(ArmorDamageType.CHEMICAL, TANK_SHELL_MODULE_DAMAGE * 0.40D,
            100.0D, 3.0D);

    private static final Map<String, ProjectileArmorEffect> CANNON_SHELL_EFFECTS = Map.of(
            "AP", APFSDS,
            "HEAT", HEAT_FS,
            "HEAT_FS", HEAT_FS,
            "HEATFS", HEAT_FS
    );
    private static final Map<String, Double> HMG_PROJECTILE_PENETRATION_MM = Map.of(
            "t72b", 29.0D,
            "mi24v", 29.0D
    );

    /**
     * Exact typed weapon identities whose current BVP APFSDS basis is non-default.  Shared HE
     * round IDs (notably 3OF26) are resolved through this weapon identity, never round ID alone.
     * Other typed tank weapons retain the established generic 175-point APFSDS basis.
     */
    private static final Map<String, Double> APFSDS_HULL_DAMAGE_BY_WEAPON = Map.ofEntries(
            Map.entry("t64b_obr1976/cannon", 250.0D),
            Map.entry("t72b/cannon", 250.0D),
            Map.entry("t80b_obr1976/cannon", 250.0D),
            Map.entry("t80u_obr1985/cannon", 250.0D),
            Map.entry("t72b3/cannon", 280.0D),
            Map.entry("t72b3_ubh_cope/cannon", 280.0D),
            Map.entry("t90a/cannon", 280.0D),
            Map.entry("t80bvm_obr2022/cannon", 280.0D)
    );

    private static final Map<String, Double> APFSDS_HULL_DAMAGE_BY_PROFILE = Map.ofEntries(
            Map.entry("t64b_obr1976", 250.0D),
            Map.entry("t72b", 250.0D),
            Map.entry("t80b_obr1976", 250.0D),
            Map.entry("t80u_obr1985", 250.0D),
            Map.entry("t72b3", 280.0D),
            Map.entry("t72b3_ubh_cope", 280.0D),
            Map.entry("t90a", 280.0D),
            Map.entry("t80bvm_obr2022", 280.0D)
    );

    private ProjectileArmorEffects() {
    }

    static ProjectileArmorEffect cannonShellEffect(String shellTypeName, ProjectileArmorEffect fallback) {
        ProjectileArmorEffect effect = CANNON_SHELL_EFFECTS.get(shellTypeName);
        return effect == null ? fallback : effect;
    }

    static Double hmgPenetrationMm(String shooterProfileId) {
        return HMG_PROJECTILE_PENETRATION_MM.get(shooterProfileId);
    }

    /**
     * Applies the source-owned armor-effect damage normalization to a typed profile.  The
     * descriptor is the only classification input; projectile/profile display names and gun
     * defaults are intentionally ignored.  Penetration, tracer/belt identity, and effect type
     * remain on the incoming effect.
     */
    static ProjectileArmorEffect normalizeProfiledDamage(Projectile projectile,
                                                         ProjectileArmorEffect effect,
                                                         ProjectileCombatDescriptor descriptor,
                                                         String shooterProfileId) {
        if (effect == null || descriptor == null || !descriptor.hasPrecomputedDamage()) {
            // An incomplete typed profile is not a legacy damage permission.  The classifier
            // rejects it before this seam; keep this helper fail-closed for defensive callers.
            return null;
        }
        int hullDamage = descriptor.getHullDamage();
        int moduleDamage = descriptor.getModuleDamage();
        int ammoRackDamage = descriptor.getAmmoRackDamage();
        if (!validPrecomputedDamage(hullDamage)
                || !validPrecomputedDamage(moduleDamage)
                || !validPrecomputedDamage(ammoRackDamage)) {
            return null;
        }
        // Damage normalization 2026-09-28 (tools/damage/balance.py): HullDamage is the whole hull damage of a
        // penetrating hit, ModuleDamage goes to every module the shot crosses (ammo racks included), and
        // AmmoRackDamage is the per-mille chance that a crossed rack goes up at once.
        return effect.withArmorDamage(moduleDamage, moduleDamage, hullDamage)
                .withAmmoRackChance(ammoRackDamage / 1000.0D);
    }

    private static boolean validPrecomputedDamage(int value) {
        return value >= 0;
    }

    /** Applies the same typed HE rule to a legacy shell path with no profile descriptor. */
    static double tankHeVehicleDamageForShooterProfile(String shooterProfileId) {
        Double apfsds = APFSDS_HULL_DAMAGE_BY_PROFILE.get(normalizeToken(shooterProfileId));
        return (apfsds == null ? TANK_SHELL_VEHICLE_DAMAGE : apfsds) * 2.0D;
    }

    private static String normalizeToken(String token) {
        return token == null ? "" : token.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    static boolean hasImpactVisual(ProjectileArmorEffect shot) {
        return shot != null && shot.impactVisual != ProjectileArmorEffect.ImpactVisual.NONE;
    }

    private static ProjectileArmorEffect nonTandemAtgm(double vehicleDamage) {
        return atgm(NON_TANDEM_ATGM_MODULE_DAMAGE, vehicleDamage, 7.0D);
    }

    private static ProjectileArmorEffect tandemAtgm(double vehicleDamage, double ammoRackDamage) {
        return atgm(TANDEM_ATGM_MODULE_DAMAGE, vehicleDamage, ammoRackDamage).withTandemWarhead();
    }

    private static ProjectileArmorEffect atgm(double moduleDamage, double vehicleDamage, double ammoRackDamage) {
        return effect(ArmorDamageType.CHEMICAL, moduleDamage, vehicleDamage, ammoRackDamage)
                .withImpactVisual(ProjectileArmorEffect.ImpactVisual.ATGM);
    }

    private static ProjectileArmorEffect effect(ArmorDamageType damageType, double moduleDamage) {
        return new ProjectileArmorEffect(damageType, 0.0D, null, moduleDamage);
    }

    private static ProjectileArmorEffect effect(ArmorDamageType damageType, double moduleDamage,
                                                double vehicleDamage) {
        return effect(damageType, moduleDamage).withVehicleDamage(vehicleDamage);
    }

    private static ProjectileArmorEffect effect(ArmorDamageType damageType, double moduleDamage,
                                                double vehicleDamage, double ammoRackDamage) {
        return new ProjectileArmorEffect(damageType, 0.0D, null, moduleDamage,
                Map.of(ArmorModuleResolver.AMMO_RACK, ammoRackDamage)).withVehicleDamage(vehicleDamage);
    }
}
