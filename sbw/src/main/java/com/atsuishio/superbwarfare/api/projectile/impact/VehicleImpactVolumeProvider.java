package com.atsuishio.superbwarfare.api.projectile.impact;

@FunctionalInterface
public interface VehicleImpactVolumeProvider {
    /**
     * Returns a provider-owned view for this coarse entity impact, or {@code null} when unsupported.
     * This callback is invoked only on the logical server and only for entity impact contexts.
     */
    VehicleImpactVolumeView query(ProjectileImpactContext context);
}
