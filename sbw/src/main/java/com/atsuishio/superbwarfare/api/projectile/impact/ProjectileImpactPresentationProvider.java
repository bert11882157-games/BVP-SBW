package com.atsuishio.superbwarfare.api.projectile.impact;

/** Server-side post-resolution projectile-impact presentation extension. */
@FunctionalInterface
public interface ProjectileImpactPresentationProvider {
    /**
     * Returns true only after handling the complete replacement presentation for this impact.
     * Gameplay has already been resolved when this method runs.
     */
    boolean present(ProjectileImpactContext context, ProjectileImpactResult result);
}
