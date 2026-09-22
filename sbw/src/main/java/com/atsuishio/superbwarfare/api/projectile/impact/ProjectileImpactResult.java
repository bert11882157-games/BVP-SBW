package com.atsuishio.superbwarfare.api.projectile.impact;

import java.util.Objects;

/**
 * Typed impact decision. Gameplay suppression and visual suppression are deliberately independent.
 */
public final class ProjectileImpactResult {
    private static final ProjectileImpactResult DEFAULT = builder(ProjectileImpactDisposition.DEFAULT).build();

    private final ProjectileImpactDisposition disposition;
    private final ProjectileImpactVisualPolicy visualPolicy;
    private final ProjectileImpactPresentationOutcome presentationOutcome;
    private final boolean consumeProjectile;
    private final boolean suppressNativeModuleDamage;
    private final boolean suppressDefaultExplosion;
    private final Float residualDamage;
    private final Float residualExplosionDamage;
    private final Float residualExplosionRadius;

    private ProjectileImpactResult(Builder builder) {
        this.disposition = builder.disposition;
        this.visualPolicy = builder.visualPolicy;
        this.presentationOutcome = builder.presentationOutcome;
        this.consumeProjectile = builder.consumeProjectile;
        this.suppressNativeModuleDamage = builder.suppressNativeModuleDamage;
        this.suppressDefaultExplosion = builder.suppressDefaultExplosion;
        this.residualDamage = builder.residualDamage;
        this.residualExplosionDamage = builder.residualExplosionDamage;
        this.residualExplosionRadius = builder.residualExplosionRadius;
    }

    public static ProjectileImpactResult defaultResult() {
        return DEFAULT;
    }

    public static Builder builder(ProjectileImpactDisposition disposition) {
        return new Builder(disposition);
    }

    public ProjectileImpactDisposition getDisposition() {
        return disposition;
    }

    public ProjectileImpactVisualPolicy getVisualPolicy() {
        return visualPolicy;
    }

    public ProjectileImpactPresentationOutcome getPresentationOutcome() {
        return presentationOutcome;
    }

    public boolean consumesProjectile() {
        return consumeProjectile;
    }

    public boolean suppressesNativeModuleDamage() {
        return suppressNativeModuleDamage;
    }

    public boolean suppressesDefaultExplosion() {
        return suppressDefaultExplosion;
    }

    public Float getResidualDamage() {
        return residualDamage;
    }

    public Float getResidualExplosionDamage() {
        return residualExplosionDamage;
    }

    public Float getResidualExplosionRadius() {
        return residualExplosionRadius;
    }

    public boolean continuesDefaultPipeline() {
        return disposition == ProjectileImpactDisposition.DEFAULT
                || disposition == ProjectileImpactDisposition.PENETRATE;
    }

    public boolean suppressesDefaultVisuals() {
        return visualPolicy != ProjectileImpactVisualPolicy.DEFAULT;
    }

    /**
     * True when this result changes gameplay or presentation from the default result.
     * In particular, DEFAULT disposition plus REPLACE visuals is a valid override.
     */
    public boolean hasOverrides() {
        return disposition != ProjectileImpactDisposition.DEFAULT
                || visualPolicy != ProjectileImpactVisualPolicy.DEFAULT
                || presentationOutcome != ProjectileImpactPresentationOutcome.DEFAULT
                || consumeProjectile
                || suppressNativeModuleDamage
                || suppressDefaultExplosion
                || residualDamage != null
                || residualExplosionDamage != null
                || residualExplosionRadius != null;
    }

    public static final class Builder {
        private final ProjectileImpactDisposition disposition;
        private ProjectileImpactVisualPolicy visualPolicy = ProjectileImpactVisualPolicy.DEFAULT;
        private ProjectileImpactPresentationOutcome presentationOutcome =
                ProjectileImpactPresentationOutcome.DEFAULT;
        private boolean consumeProjectile;
        private boolean suppressNativeModuleDamage;
        private boolean suppressDefaultExplosion;
        private Float residualDamage;
        private Float residualExplosionDamage;
        private Float residualExplosionRadius;

        private Builder(ProjectileImpactDisposition disposition) {
            this.disposition = Objects.requireNonNull(disposition, "disposition");
            if (disposition == ProjectileImpactDisposition.BLOCK
                    || disposition == ProjectileImpactDisposition.CONSUME) {
                this.consumeProjectile = true;
                this.suppressDefaultExplosion = true;
            } else if (disposition == ProjectileImpactDisposition.PASS) {
                this.suppressDefaultExplosion = true;
            }
        }

        public Builder visualPolicy(ProjectileImpactVisualPolicy visualPolicy) {
            this.visualPolicy = Objects.requireNonNull(visualPolicy, "visualPolicy");
            return this;
        }

        public Builder presentationOutcome(ProjectileImpactPresentationOutcome presentationOutcome) {
            this.presentationOutcome = Objects.requireNonNull(presentationOutcome, "presentationOutcome");
            return this;
        }

        public Builder consumeProjectile(boolean consumeProjectile) {
            this.consumeProjectile = consumeProjectile;
            return this;
        }

        public Builder suppressNativeModuleDamage(boolean suppressNativeModuleDamage) {
            this.suppressNativeModuleDamage = suppressNativeModuleDamage;
            return this;
        }

        public Builder suppressDefaultExplosion(boolean suppressDefaultExplosion) {
            this.suppressDefaultExplosion = suppressDefaultExplosion;
            return this;
        }

        public Builder residualDamage(float residualDamage) {
            this.residualDamage = Math.max(0.0F, residualDamage);
            return this;
        }

        public Builder residualExplosion(float damage, float radius) {
            this.residualExplosionDamage = Math.max(0.0F, damage);
            this.residualExplosionRadius = Math.max(0.0F, radius);
            return this;
        }

        public ProjectileImpactResult build() {
            return new ProjectileImpactResult(this);
        }
    }
}
