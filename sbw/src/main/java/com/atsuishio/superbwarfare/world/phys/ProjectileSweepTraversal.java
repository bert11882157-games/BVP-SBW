package com.atsuishio.superbwarfare.world.phys;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import java.util.function.Predicate;

/** Dispatches the block-clipped ordered entity contacts, then the remaining block contact. */
public final class ProjectileSweepTraversal {
    private ProjectileSweepTraversal() { }

    /** A reflected round remains alive but cannot visit contacts on its old straight-line sweep. */
    public static boolean stops(ProjectileImpactResult result) {
        return result.consumesProjectile() || result.getDisposition() == ProjectileImpactDisposition.BLOCK
                || result.getDisposition() == ProjectileImpactDisposition.CONSUME
                || result.getPresentationOutcome() == ProjectileImpactPresentationOutcome.RICOCHET;
    }

    public static <T> void visit(Iterable<T> contacts, Predicate<T> continuesAfter, Runnable terminal) {
        for (T contact : contacts) {
            if (!continuesAfter.test(contact)) return;
        }
        terminal.run();
    }
}
