package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ProjectileTrailKind;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.world.entity.Entity;

/** Trail presentation probes use the shared Elite session; no separate map, logger or switch. */
public final class BvpTrailDiagnostics {
    private BvpTrailDiagnostics() {}

    public static void recordBvpTrailSpawn(Entity entity, BvpProjectileEffectDefinition definition,
                                           ProjectileTrailKind kind, double scale,
                                           int centerSamples, int satelliteSamples,
                                           int flameParticles, int smokeParticles, long tick) {
        if (entity == null || definition == null || !EliteDiagnostics.isClientEnabled()
                || (tick + entity.m_19879_()) % 10 != 0) return;
        EliteDiagnostics.record(entity, "effects", "trail_spawn",
                "profile", ProjectileProfiles.profileId(entity), "kind", kind, "scale", scale,
                "center_samples", centerSamples, "satellite_samples", satelliteSamples,
                "flame_particles", flameParticles, "smoke_particles", smokeParticles,
                "position", entity.m_20182_(), "velocity", entity.m_20184_());
    }

    public static void recordProviderFallback(Entity entity, ProjectileTrailKind kind, String reason, long tick) {
        if (entity == null || !EliteDiagnostics.isClientEnabled()
                || (tick + entity.m_19879_()) % 10 != 0) return;
        EliteDiagnostics.record(entity, "effects", "trail_fallback",
                "profile", ProjectileProfiles.profileId(entity), "kind", kind, "reason", reason);
    }
}
