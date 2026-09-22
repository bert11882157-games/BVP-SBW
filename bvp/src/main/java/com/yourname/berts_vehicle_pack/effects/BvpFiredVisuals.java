package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualInterestContext;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualInterests;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualProviders;
import com.atsuishio.superbwarfare.api.weapon.FiredVisualRecord;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.client.BvpFiredVisualClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/** BVP's optional presentation provider for SBW's authoritative fired-visual records. */
public final class BvpFiredVisuals {
    private static final ResourceLocation PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "fired_visuals");

    private BvpFiredVisuals() {
    }

    public static void register() {
        FiredVisualInterests.register(PROVIDER_ID, BvpFiredVisuals::isInterested);
        FiredVisualProviders.register(PROVIDER_ID, BvpFiredVisuals::handle);
    }

    private static boolean isInterested(FiredVisualInterestContext context) {
        return classify(context.getWeaponId(), context.getProjectileProfileId()) != VisualKind.NONE;
    }

    private static boolean handle(FiredVisualRecord record) {
        VisualKind kind = classify(record);
        if (kind == VisualKind.NONE) {
            return false;
        }
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BvpFiredVisualClient.handle(record, kind));
        return true;
    }

    private static VisualKind classify(FiredVisualRecord record) {
        return classify(record.getWeaponId(), record.getProjectileProfileId());
    }

    private static VisualKind classify(ResourceLocation weaponId, ResourceLocation profileId) {
        if ((weaponId == null || !BertsVehiclePack.MODID.equals(weaponId.m_135827_()))
                && (profileId == null || !BertsVehiclePack.MODID.equals(profileId.m_135827_()))) {
            return VisualKind.NONE;
        }

        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(profileId);
        ProjectileCombatDescriptor combat = profile == null ? null : profile.getCombat();
        ResourceLocation munitionType = combat == null ? null : combat.getMunitionType();
        ResourceLocation roundId = combat == null ? null : combat.getRoundId();
        if (munitionType != null && BertsVehiclePack.MODID.equals(munitionType.m_135827_())
                && "tank_shell".equals(munitionType.m_135815_())) {
            return VisualKind.TANK_CANNON;
        }
        if (munitionType != null && BertsVehiclePack.MODID.equals(munitionType.m_135827_())
                && "autocannon_shell".equals(munitionType.m_135815_())) {
            return VisualKind.AUTOCANNON;
        }
        if (munitionType != null && BertsVehiclePack.MODID.equals(munitionType.m_135827_())
                && "bullet".equals(munitionType.m_135815_())) {
            // Mounted guns use authored names such as DShK, PKT and Cannon. Presentation
            // follows the resolved round, not a small whitelist of weapon-channel names.
            Double caliber = combat.getCaliberMm();
            return caliber != null && caliber >= 12.0 ? VisualKind.PASSENGER_HMG : VisualKind.COAX;
        }
        if (weaponId == null) {
            return VisualKind.NONE;
        }

        String path = weaponId.m_135815_();
        if (path.endsWith("/passengermachinegun") || path.endsWith("/heavymachinegun")) {
            return VisualKind.PASSENGER_HMG;
        }
        if (path.endsWith("/machinegun") || path.endsWith("/mainmachinegun")) {
            return VisualKind.COAX;
        }
        if (path.endsWith("/cannon") && roundId != null
                && BertsVehiclePack.MODID.equals(roundId.m_135827_())
                && "30mm_grapeshot".equals(roundId.m_135815_())) {
            return VisualKind.AUTOCANNON;
        }
        if (path.endsWith("/cannon")
                && (path.startsWith("mi24v/") || path.startsWith("mi28n/") || path.startsWith("ka50/"))) {
            return VisualKind.AUTOCANNON;
        }
        return VisualKind.NONE;
    }

    public enum VisualKind {
        NONE,
        TANK_CANNON,
        AUTOCANNON,
        PASSENGER_HMG,
        COAX
    }
}
