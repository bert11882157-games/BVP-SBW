package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;

/** Gameplay admission over already-validated profile values; optional effect data is not read. */
final class BvpBlockImpactAdmission {
    enum CombatState { UNPROFILED, INVALID, COMPLETE_BVP, COMPLETE_OTHER }

    private BvpBlockImpactAdmission() {}

    static CombatState combatState(ResourceLocation declaredId, ResolvedProjectileProfile profile) {
        if (declaredId == null && profile == null) return CombatState.UNPROFILED;
        if (profile == null || profile.getCombat() == null) return CombatState.INVALID;
        return BertsVehiclePack.MODID.equals(profile.getId().m_135827_())
                ? CombatState.COMPLETE_BVP : CombatState.COMPLETE_OTHER;
    }

    static boolean admits(boolean classified, boolean legacyImpactVisual, CombatState combat) {
        return classified && combat != CombatState.INVALID
                && (combat == CombatState.COMPLETE_BVP || legacyImpactVisual);
    }
}
