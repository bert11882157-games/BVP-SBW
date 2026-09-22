package com.yourname.berts_vehicle_pack.armor;

import java.util.Map;

final class AtgmArmorProfiles {
    private static final Profile BASTION = nonTandem(ProjectileArmorEffects.ATGM_9M117_BASTION);
    private static final Profile SHTURM = nonTandem(ProjectileArmorEffects.ATGM_9M114_SHTURM);
    private static final Profile ATAKA = tandem(ProjectileArmorEffects.ATGM_9M120_ATAKA);
    private static final Profile VIKHR = tandem(ProjectileArmorEffects.ATGM_9K127_VIKHR);
    private static final Profile DEFAULT_ATGM = nonTandem(ProjectileArmorEffects.DEFAULT_ATGM);
    private static final Profile KONKURS = nonTandem(ProjectileArmorEffects.ATGM_9M113_KONKURS);
    private static final Profile T90A_TANDEM = tandem(ProjectileArmorEffects.ATGM_9M119M1_TANDEM);

    private static final Map<String, Profile> PROFILE_BY_SHOOTER_ID = Map.of(
            ProjectileArmorEffects.T62M1_PROFILE_ID, BASTION,
            ProjectileArmorEffects.MI28N_PROFILE_ID, ATAKA,
            ProjectileArmorEffects.BMPT_PROFILE_ID, ATAKA,
            ProjectileArmorEffects.KA50_PROFILE_ID, VIKHR,
            ProjectileArmorEffects.MI24V_PROFILE_ID, SHTURM,
            ProjectileArmorEffects.BMP2_PROFILE_ID, KONKURS,
            ProjectileArmorEffects.T90A_PROFILE_ID, T90A_TANDEM
    );

    private AtgmArmorProfiles() {
    }

    static Profile forShot(String shooterProfileId, int durability) {
        Profile profile = shooterProfileId == null ? null : PROFILE_BY_SHOOTER_ID.get(shooterProfileId);
        if (profile == null) {
            profile = durability == (int) ProjectileArmorEffects.TANDEM_ATGM_PENETRATION_MM
                    ? T90A_TANDEM
                    : DEFAULT_ATGM;
        }
        return withConfiguredTags(shooterProfileId, profile);
    }

    private static Profile withConfiguredTags(String shooterProfileId, Profile profile) {
        if (shooterProfileId == null || shooterProfileId.isEmpty()
                || !ArmorProfiles.get(shooterProfileId).atgmTandemWarhead) {
            return profile;
        }
        return profile(profile.penetrationMm(), profile.effect().withTandemWarhead());
    }

    private static Profile nonTandem(ProjectileArmorEffect effect) {
        return profile(ProjectileArmorEffects.NON_TANDEM_ATGM_PENETRATION_MM, effect);
    }

    private static Profile tandem(ProjectileArmorEffect effect) {
        return profile(ProjectileArmorEffects.TANDEM_ATGM_PENETRATION_MM, effect);
    }

    private static Profile profile(double penetrationMm, ProjectileArmorEffect effect) {
        return new Profile(penetrationMm, effect);
    }

    record Profile(double penetrationMm, ProjectileArmorEffect effect) {
    }
}
