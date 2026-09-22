package com.atsuishio.superbwarfare.api.projectile.impact;

@FunctionalInterface
public interface ProjectileImpactHandler {
    ProjectileImpactResult resolve(ProjectileImpactContext context);
}
