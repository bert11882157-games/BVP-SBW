package com.atsuishio.superbwarfare.api.vehicle.destruction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Immutable server-side policy and attribution for one vehicle destruction.
 *
 * <p>Null optional values delegate to Superb Warfare's native behavior. In
 * particular, a null turret impulse retains the configured randomized native
 * impulse and null presentation IDs retain the normal SBW wreck/explosion
 * presentation.</p>
 */
public final class VehicleDestructionContext {
    @Nullable
    private final Entity directSource;
    @Nullable
    private final Entity attacker;
    private final TurretEjectionPolicy turretPolicy;
    @Nullable
    private final Vec3 turretImpulse;
    @Nullable
    private final ResourceLocation turretCrushPolicyId;
    @Nullable
    private final ResourceLocation explosionCauseId;
    @Nullable
    private final ResourceLocation explosionProfileId;
    @Nullable
    private final ResourceLocation wreckVisualId;
    @Nullable
    private final Vec3 gameplayPosition;
    @Nullable
    private final Vec3 particlePosition;
    private final boolean emitExplosionFx;

    private VehicleDestructionContext(Builder builder) {
        this.directSource = builder.directSource;
        this.attacker = builder.attacker;
        this.turretPolicy = Objects.requireNonNull(builder.turretPolicy, "turretPolicy");
        this.turretImpulse = builder.turretImpulse;
        this.turretCrushPolicyId = builder.turretCrushPolicyId;
        this.explosionCauseId = builder.explosionCauseId;
        this.explosionProfileId = builder.explosionProfileId;
        this.wreckVisualId = builder.wreckVisualId;
        this.gameplayPosition = builder.gameplayPosition;
        this.particlePosition = builder.particlePosition;
        this.emitExplosionFx = builder.emitExplosionFx;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Nullable
    public Entity directSource() {
        return directSource;
    }

    @Nullable
    public Entity attacker() {
        return attacker;
    }

    public TurretEjectionPolicy turretPolicy() {
        return turretPolicy;
    }

    @Nullable
    public Vec3 turretImpulse() {
        return turretImpulse;
    }

    @Nullable
    public ResourceLocation turretCrushPolicyId() {
        return turretCrushPolicyId;
    }

    @Nullable
    public ResourceLocation explosionCauseId() {
        return explosionCauseId;
    }

    @Nullable
    public ResourceLocation explosionProfileId() {
        return explosionProfileId;
    }

    @Nullable
    public ResourceLocation wreckVisualId() {
        return wreckVisualId;
    }

    @Nullable
    public Vec3 gameplayPosition() {
        return gameplayPosition;
    }

    @Nullable
    public Vec3 particlePosition() {
        return particlePosition;
    }

    public boolean emitExplosionFx() {
        return emitExplosionFx;
    }

    public static final class Builder {
        @Nullable
        private Entity directSource;
        @Nullable
        private Entity attacker;
        private TurretEjectionPolicy turretPolicy = TurretEjectionPolicy.DEFAULT;
        @Nullable
        private Vec3 turretImpulse;
        @Nullable
        private ResourceLocation turretCrushPolicyId;
        @Nullable
        private ResourceLocation explosionCauseId;
        @Nullable
        private ResourceLocation explosionProfileId;
        @Nullable
        private ResourceLocation wreckVisualId;
        @Nullable
        private Vec3 gameplayPosition;
        @Nullable
        private Vec3 particlePosition;
        private boolean emitExplosionFx = true;

        private Builder() {
        }

        private Builder(VehicleDestructionContext context) {
            this.directSource = context.directSource;
            this.attacker = context.attacker;
            this.turretPolicy = context.turretPolicy;
            this.turretImpulse = context.turretImpulse;
            this.turretCrushPolicyId = context.turretCrushPolicyId;
            this.explosionCauseId = context.explosionCauseId;
            this.explosionProfileId = context.explosionProfileId;
            this.wreckVisualId = context.wreckVisualId;
            this.gameplayPosition = context.gameplayPosition;
            this.particlePosition = context.particlePosition;
            this.emitExplosionFx = context.emitExplosionFx;
        }

        public Builder directSource(@Nullable Entity directSource) {
            this.directSource = directSource;
            return this;
        }

        public Builder attacker(@Nullable Entity attacker) {
            this.attacker = attacker;
            return this;
        }

        public Builder turretPolicy(TurretEjectionPolicy turretPolicy) {
            this.turretPolicy = Objects.requireNonNull(turretPolicy, "turretPolicy");
            return this;
        }

        public Builder turretImpulse(@Nullable Vec3 turretImpulse) {
            this.turretImpulse = turretImpulse;
            return this;
        }

        public Builder turretCrushPolicy(@Nullable ResourceLocation turretCrushPolicyId) {
            this.turretCrushPolicyId = turretCrushPolicyId;
            return this;
        }

        public Builder explosionCause(@Nullable ResourceLocation explosionCauseId) {
            this.explosionCauseId = explosionCauseId;
            return this;
        }

        public Builder explosionProfile(@Nullable ResourceLocation explosionProfileId) {
            this.explosionProfileId = explosionProfileId;
            return this;
        }

        public Builder wreckVisual(@Nullable ResourceLocation wreckVisualId) {
            this.wreckVisualId = wreckVisualId;
            return this;
        }

        public Builder gameplayPosition(@Nullable Vec3 gameplayPosition) {
            this.gameplayPosition = gameplayPosition;
            return this;
        }

        public Builder particlePosition(@Nullable Vec3 particlePosition) {
            this.particlePosition = particlePosition;
            return this;
        }

        public Builder emitExplosionFx(boolean emitExplosionFx) {
            this.emitExplosionFx = emitExplosionFx;
            return this;
        }

        public VehicleDestructionContext build() {
            return new VehicleDestructionContext(this);
        }
    }
}
