package com.atsuishio.superbwarfare.api.projectile;

/** Read-only bounds for supplemental server simulation; never changes ballistics or impact policy. */
public interface FarProjectileAccess {
    double farProjectileExplosionRadius();

    /** Horizontal residency margin required by this projectile's actual entity collision query. */
    default double farProjectileCollisionPadding() { return 9.0; }

    /** Extra terrain lookahead performed after the ordinary movement step (cluster/proximity rounds). */
    default int farProjectileLookAheadTicks() { return 0; }

    /** Native maximum age, where exposed. The elapsed-world backstop never exceeds120 seconds. */
    default int farProjectileLifetimeTicks() { return 2400; }

    /** Ordinary projectiles retain a seven-second backstop; bombs and missiles use their bounded authored flight. */
    default int farProjectileMaximumLifetimeTicks() { return 140; }

    /** True only when the next real tick executes the native terminal expiry (including its burst). */
    default boolean farProjectileTerminatesNextTick(int currentAge) { return false; }
}
