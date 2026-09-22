package com.atsuishio.superbwarfare.api.projectile.impact;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Immutable, server-authoritative input for a projectile impact resolver. */
public final class ProjectileImpactContext {
    public enum Kind {
        ENTITY,
        BLOCK
    }

    private final Kind kind;
    private final Entity owner;
    private final Projectile projectile;
    private final Vec3 hitVec;
    private final Vec3 incomingVelocity;
    private final Entity target;
    private final BlockPos blockPos;
    private final BlockState blockState;
    private final Direction blockFace;
    private final DamageSource damageSource;
    private final VehicleImpactVolumeResult vehicleImpactVolumes;

    private ProjectileImpactContext(Kind kind, Entity owner, Projectile projectile, Vec3 hitVec,
                                    Entity target, BlockPos blockPos, BlockState blockState,
                                    Direction blockFace, DamageSource damageSource,
                                    VehicleImpactVolumeResult vehicleImpactVolumes, Vec3 incomingVelocity) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.owner = owner;
        this.projectile = Objects.requireNonNull(projectile, "projectile");
        this.hitVec = Objects.requireNonNull(hitVec, "hitVec");
        this.incomingVelocity = Objects.requireNonNull(incomingVelocity, "incomingVelocity");
        this.target = target;
        this.blockPos = blockPos;
        this.blockState = blockState;
        this.blockFace = blockFace;
        this.damageSource = damageSource;
        this.vehicleImpactVolumes = Objects.requireNonNull(vehicleImpactVolumes, "vehicleImpactVolumes");
    }

    public static ProjectileImpactContext entity(Entity owner, Projectile projectile,
                                                 Entity target, Vec3 hitVec) {
        return entity(owner, projectile, target, hitVec,
                target.damageSources().thrown(projectile, owner));
    }

    public static ProjectileImpactContext entity(Entity owner, Projectile projectile,
                                                 Entity target, Vec3 hitVec,
                                                 DamageSource damageSource) {
        return new ProjectileImpactContext(Kind.ENTITY, owner, projectile, hitVec,
                Objects.requireNonNull(target, "target"), null, null, null,
                Objects.requireNonNull(damageSource, "damageSource"),
                VehicleImpactVolumeResult.empty(), projectile.getDeltaMovement());
    }

    public static ProjectileImpactContext block(Entity owner, Projectile projectile, Vec3 hitVec,
                                                BlockPos blockPos, BlockState blockState,
                                                Direction blockFace) {
        return new ProjectileImpactContext(Kind.BLOCK, owner, projectile, hitVec, null,
                Objects.requireNonNull(blockPos, "blockPos"),
                Objects.requireNonNull(blockState, "blockState"),
                Objects.requireNonNull(blockFace, "blockFace"), null,
                VehicleImpactVolumeResult.empty(), projectile.getDeltaMovement());
    }

    ProjectileImpactContext withVehicleImpactVolumes(VehicleImpactVolumeResult result) {
        return new ProjectileImpactContext(kind, owner, projectile, hitVec, target, blockPos, blockState,
                blockFace, damageSource, result, incomingVelocity);
    }

    public Kind getKind() {
        return kind;
    }

    public Entity getOwner() {
        return owner;
    }

    public Projectile getProjectile() {
        return projectile;
    }

    public Vec3 getHitVec() {
        return hitVec;
    }

    /** World blocks per tick, captured before a resolver can reflect or consume the projectile. */
    public Vec3 getIncomingVelocity() {
        return incomingVelocity;
    }

    public Entity getTarget() {
        return target;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public BlockState getBlockState() {
        return blockState;
    }

    public Direction getBlockFace() {
        return blockFace;
    }

    /** Exact native direct-hit source for entity impacts; null for block impacts. */
    public DamageSource getDamageSource() {
        return damageSource;
    }

    public VehicleImpactVolumeResult getVehicleImpactVolumes() {
        return vehicleImpactVolumes;
    }
}
