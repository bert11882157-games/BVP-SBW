package com.atsuishio.superbwarfare.api.projectile.impact;

/**
 * Gameplay ownership selected before Superb Warfare runs a projectile's native impact path.
 */
public enum ProjectileImpactDisposition {
    /** Preserve the complete native Superb Warfare impact path. */
    DEFAULT,
    /** Ignore this collision and keep the projectile alive. */
    PASS,
    /** The collision stopped the projectile without native damage or explosion handling. */
    BLOCK,
    /** Continue the native impact path, optionally with residual damage values. */
    PENETRATE,
    /** An extension fully handled the collision and consumed the projectile. */
    CONSUME
}
