package com.atsuishio.superbwarfare.api.projectile.impact;

/**
 * Final gameplay-owned impact classification exposed to presentation providers.
 * This records an already-resolved branch and must never be used to decide damage.
 */
public enum ProjectileImpactPresentationOutcome {
    /** No more specific presentation outcome was recorded. */
    DEFAULT,
    /** The resolved hit used the non-penetration presentation branch. */
    NON_PENETRATION,
    /** The resolved hit ricocheted and physically deflected before native damage. */
    RICOCHET,
    /** The resolved hit used the penetration presentation branch. */
    PENETRATION
}
