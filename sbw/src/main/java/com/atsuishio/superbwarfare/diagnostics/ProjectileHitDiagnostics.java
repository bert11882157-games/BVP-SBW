package com.atsuishio.superbwarfare.diagnostics;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Contact candidates are attributed to the target so entity-scoped captures include misses. */
public final class ProjectileHitDiagnostics {
    private ProjectileHitDiagnostics() { }

    public static @Nullable ProjectileCollisionTarget.Hit query(Entity projectile, Entity target,
            String path, Vec3 start, Vec3 end, @Nullable ProjectileCollisionTarget.Hit hit) {
        if (EliteDiagnostics.isEnabled(target.level())) {
            EliteDiagnostics.record(target, "hitreg", "contact_query",
                    "projectile", projectile.getUUID(), "projectile_type", projectile.getType(),
                    "path", path, "start", start, "end", end, "velocity", projectile.getDeltaMovement(),
                    "target_position", target.position(), "target_velocity", target.getDeltaMovement(),
                    "target_yaw", target.getYRot(), "target_pitch", target.getXRot(),
                    "hit", hit != null, "point", hit == null ? null : hit.point(),
                    "part", hit == null ? null : hit.part());
        }
        return hit;
    }
}
