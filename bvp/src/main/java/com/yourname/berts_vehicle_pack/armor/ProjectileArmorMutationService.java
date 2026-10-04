package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactDisposition;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactPresentationOutcome;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactResult;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactVisualPolicy;
import com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile;

final class ProjectileArmorMutationService {
    private static final double CHEMICAL_DAMAGE_MULTIPLIER = 0.80D;

    private ProjectileArmorMutationService() {
    }

    static ProjectileImpactResult blockImpact(boolean replacementVisual,
                                              ProjectileImpactPresentationOutcome presentationOutcome) {
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.BLOCK)
                .visualPolicy(replacementVisual
                        ? ProjectileImpactVisualPolicy.REPLACE
                        : ProjectileImpactVisualPolicy.SUPPRESS)
                .presentationOutcome(presentationOutcome)
                .build();
    }

    static ProjectileImpactResult continueImpact(boolean replacementVisual,
                                                 ProjectileImpactPresentationOutcome presentationOutcome) {
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.PENETRATE)
                .visualPolicy(replacementVisual
                        ? ProjectileImpactVisualPolicy.REPLACE
                        : ProjectileImpactVisualPolicy.DEFAULT)
                .presentationOutcome(presentationOutcome)
                .build();
    }

    /**
     * A ricochet continues the entity's normal flight loop after the owner has reflected its
     * velocity. PASS prevents native entity/module damage and does not consume the projectile,
     * while the explicit RICOCHET outcome still reaches the presentation provider.
     */
    /** A ricochet eats the shot (owner 2026-09-30): the round is consumed; its tracer is cosmetic, client side. */
    static ProjectileImpactResult ricochetImpact(boolean replacementVisual) {
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.BLOCK)
                .visualPolicy(replacementVisual
                        ? ProjectileImpactVisualPolicy.REPLACE
                        : ProjectileImpactVisualPolicy.SUPPRESS)
                .presentationOutcome(ProjectileImpactPresentationOutcome.RICOCHET)
                .build();
    }

    /**
     * The contact is not a vehicle hit: no damage, no presentation, and the projectile keeps
     * flying on its current velocity.
     */
    static ProjectileImpactResult passImpact() {
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.PASS)
                .visualPolicy(ProjectileImpactVisualPolicy.SUPPRESS)
                .presentationOutcome(ProjectileImpactPresentationOutcome.DEFAULT)
                .build();
    }

    static ProjectileImpactResult blockApfsdsBlockImpact() {
        // APFSDS is terminal on geometric block contact.  Vehicle armor/module penetration
        // remains handled by ArmorImpactService; only the block path consumes the round.
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.BLOCK)
                .visualPolicy(ProjectileImpactVisualPolicy.REPLACE)
                .presentationOutcome(ProjectileImpactPresentationOutcome.NON_PENETRATION)
                .suppressDefaultExplosion(true)
                .build();
    }

    static ProjectileImpactResult penetratingShellResult(FastThrowableProjectile shell,
                                                          ArmorDamageType damageType,
                                                          boolean criticalHit,
                                                          boolean replacementVisual) {
        float damage = penetratingShellDamage(shell, damageType, criticalHit);
        return ProjectileImpactResult.builder(ProjectileImpactDisposition.PENETRATE)
                .visualPolicy(replacementVisual
                        ? ProjectileImpactVisualPolicy.REPLACE
                        : ProjectileImpactVisualPolicy.DEFAULT)
                .presentationOutcome(ProjectileImpactPresentationOutcome.PENETRATION)
                .residualDamage(damage)
                .suppressNativeModuleDamage(true)
                .suppressDefaultExplosion(true)
                .build();
    }

    static float penetratingShellDamage(FastThrowableProjectile shell,
                                        ArmorDamageType damageType,
                                        boolean criticalHit) {
        float damage = shell.getDamageValue();
        float multiplier = criticalHit ? 2.0F : 1.0F;
        if (damageType == ArmorDamageType.CHEMICAL) {
            multiplier *= (float) CHEMICAL_DAMAGE_MULTIPLIER;
        }
        return damage * multiplier;
    }
}
