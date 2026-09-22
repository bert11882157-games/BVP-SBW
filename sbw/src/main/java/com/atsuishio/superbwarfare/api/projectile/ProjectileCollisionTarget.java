package com.atsuishio.superbwarfare.api.projectile;

import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Optional material geometry for server projectile selection, independent of physical collision OBBs. */
public interface ProjectileCollisionTarget {
    /** False retains the existing OBB/AABB projectile path for this target. */
    boolean usesDetailedProjectileCollision();

    /**
     * Finds the first material intersection on the bounded world-space segment, or null for a miss.
     * Called only on the logical server. Implementations must not damage entities or emit effects.
     * A miss is final and must not be broadened by OBB, AABB, pick-radius, or proximity fallback.
     */
    @Nullable Hit clipProjectile(Vec3 start, Vec3 end);

    record Hit(Vec3 point, OBB.Part part) { }
}
