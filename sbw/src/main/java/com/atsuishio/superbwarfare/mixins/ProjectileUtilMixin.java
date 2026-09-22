package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.init.ModParticleTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.tools.OBB;
import com.atsuishio.superbwarfare.world.phys.ProjectileHitSelection;
import com.atsuishio.superbwarfare.world.phys.ProjectileContact;
import com.atsuishio.superbwarfare.diagnostics.ProjectileHitDiagnostics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

import static com.atsuishio.superbwarfare.tools.ParticleTool.sendParticle;

@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void getEntityHitResult(Level pLevel, Entity pProjectile, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, float pInflationAmount, CallbackInfoReturnable<EntityHitResult> cir) {
        OBBHitter.getInstance(pProjectile).sbw$setProjectileContact(null);
        var candidates = pLevel.getEntities(pProjectile, pBoundingBox.inflate(8), pFilter);
        if (!requiresCustomQuery(candidates, pProjectile)) return;
        cir.setReturnValue(resolveEntityHits(candidates, pProjectile, pStartVec, pEndVec,
                pBoundingBox, pInflationAmount, pStartVec.distanceToSqr(pEndVec), false));
    }

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void getEntityHitResult(Entity pShooter, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, double pDistance, CallbackInfoReturnable<EntityHitResult> cir) {
        OBBHitter.getInstance(pShooter).sbw$setProjectileContact(null);
        var candidates = pShooter.level().getEntities(pShooter, pBoundingBox.inflate(8), pFilter);
        if (!requiresCustomQuery(candidates, pShooter)) return;
        cir.setReturnValue(resolveEntityHits(candidates, pShooter, pStartVec, pEndVec,
                pBoundingBox, 0.0F, pDistance, true));
    }

    private static boolean requiresCustomQuery(Iterable<Entity> entities, Entity shooter) {
        for (Entity entity : entities) {
            if (usesDetailedTarget(entity, shooter) || isNarrowAtgm(entity, shooter)
                    || (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB())) return true;
        }
        return false;
    }

    private static boolean usesDetailedTarget(Entity entity, Entity shooter) {
        return shooter instanceof Projectile && !shooter.level().isClientSide
                && entity instanceof ProjectileCollisionTarget target && target.usesDetailedProjectileCollision();
    }

    /**
     * Compares the complete candidate set before publishing metadata/effects. A detailed/OBB or
     * narrow-ATGM miss is final, and cannot re-enter through vanilla's broad AABB fallback.
     */
    private static EntityHitResult resolveEntityHits(Iterable<Entity> entities, Entity shooter,
                                                     Vec3 start, Vec3 end, AABB queryBounds,
                                                     float inflation, double maxDistance, boolean shooterOverload) {
        var nearest = new ProjectileHitSelection.Nearest<Entity>(start,
                maxDistance == 0.0D ? Double.MAX_VALUE : maxDistance);
        for (Entity entity : entities) {
            if (shooter instanceof Projectile projectile && (projectile.getOwner() == entity
                    || entity.getPassengers().contains(projectile.getOwner()))) continue;
            if (shooterOverload && (entity.getPassengers().contains(shooter)
                    || (maxDistance != 0 && entity.getRootVehicle() == shooter.getRootVehicle()
                    && !entity.canRiderInteract()))) continue;

            Vec3 point;
            OBB.Part part = null;
            if (usesDetailedTarget(entity, shooter)) {
                var hit = ProjectileHitDiagnostics.query(shooter, entity, "shell_detailed", start, end,
                        ((ProjectileCollisionTarget) entity).clipProjectile(start, end));
                if (hit == null) continue;
                point = hit.point();
                part = hit.part();
            } else if (isNarrowAtgm(entity, shooter)) {
                // No AABB/pick-radius inflation for a guided missile's interception volume.
                point = WireGuideMissileEntity.preciseInterceptionHitPoint((WireGuideMissileEntity) entity, start, end);
            } else if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                var hit = ProjectileHitDiagnostics.query(shooter, entity, "shell_obb", start, end,
                        ProjectileHitSelection.nearestObb(obbEntity.getOBBs(), start, end, entity.getPickRadius() * 2));
                if (hit == null) continue;
                point = hit.point();
                part = hit.part();
            } else {
                // The expanded broadphase only admits ordinary AABBs from the original query.
                if (!entity.getBoundingBox().intersects(queryBounds)) continue;
                var box = entity.getBoundingBox().inflate(shooterOverload ? entity.getPickRadius() : inflation);
                point = shooterOverload && box.contains(start) ? start : box.clip(start, end).orElse(null);
            }
            nearest.consider(entity, point, part);
        }
        if (nearest.target() == null) return null;
        EntityHitResult result = new EntityHitResult(nearest.target(), nearest.point());
        OBBHitter.getInstance(shooter).sbw$setProjectileContact(new ProjectileContact(
                nearest.target().getUUID(), shooter.level().getGameTime(),
                nearest.part() == null ? OBB.Part.EMPTY : nearest.part()));
        if (nearest.part() != null) emitObbHitEffects(shooter, result);
        return result;
    }

    private static boolean isNarrowAtgm(Entity entity, Entity shooter) {
        return shooter instanceof Projectile && !(shooter instanceof WireGuideMissileEntity)
                && entity instanceof WireGuideMissileEntity;
    }

    private static void emitObbHitEffects(Entity shooter, EntityHitResult hitResult) {
        if (shooter.level() instanceof ServerLevel serverLevel &&
                shooter.getDeltaMovement().lengthSqr() > 0.01 && shooter instanceof Projectile) {
            Vec3 hitPos = hitResult.getLocation();
            shooter.level().playSound(
                    null, BlockPos.containing(hitPos), ModSounds.HIT.get(), SoundSource.PLAYERS, 1, 1
            );
            sendParticle(serverLevel, ModParticleTypes.FIRE_STAR.get(), hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.2, false);
            sendParticle(serverLevel, ParticleTypes.SMOKE, hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.01, false);
        }
    }
}
