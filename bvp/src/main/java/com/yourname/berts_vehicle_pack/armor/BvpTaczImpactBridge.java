package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.entity.EntityKineticBullet;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;

/** Optional TaCZ entry point. A fired RPG receives one immutable BVP impact profile. */
public final class BvpTaczImpactBridge {
    private BvpTaczImpactBridge() { }

    public static void register() {
        MinecraftForge.EVENT_BUS.addListener(BvpTaczImpactBridge::join);
        BvpTaczAtScenarios.register();
    }

    private static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof EntityKineticBullet bullet)) return;
        var index = TimelessAPI.getCommonGunIndex(bullet.getGunId()).orElse(null);
        var explosion = index == null ? null : index.getGunData().getBulletData().getExplosionData();
        if (index == null || !BvpHandheldAtPolicy.accepts(index.getType(),
                explosion != null && explosion.isExplode())) return;
        ProjectileProfiles.assign(bullet, BvpHandheldAtPolicy.PROFILE);
        ProjectileProfiles.recordServerLaunchPosition(bullet, bullet.position());
        var combat = ProjectileProfiles.combatDescriptor(bullet);
        EliteDiagnostics.record(bullet, "tacz_at", "PROFILE_ASSIGNED", "gun", bullet.getGunId(),
                "profile", ProjectileProfiles.profileId(bullet), "valid", combat != null,
                "penetration_mm", combat == null ? null : combat.getPenetrationMm(),
                "caliber_mm", combat == null ? null : combat.getCaliberMm());
    }
}
