package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.mixin.OBBHitter;
import com.atsuishio.superbwarfare.entity.projectile.WireGuideMissileEntity;
import com.atsuishio.superbwarfare.init.ModParticleTypes;
import com.atsuishio.superbwarfare.init.ModSounds;
import com.atsuishio.superbwarfare.tools.OBB;
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
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Predicate;

import static com.atsuishio.superbwarfare.tools.ParticleTool.sendParticle;

@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void getEntityHitResult(Level pLevel, Entity pProjectile, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, float pInflationAmount, CallbackInfoReturnable<EntityHitResult> cir) {
        var candidates = pLevel.getEntities(pProjectile, pBoundingBox.inflate(8), pFilter);
        // Vanilla's swept AABB is intentionally broad, but guided ATGMs expose a much smaller
        // physical interception volume.  When one is in the candidate set, resolve the complete
        // hit list here so the original method cannot re-admit it through its broad fallback.
        if (containsNarrowAtgm(candidates, pProjectile)) {
            cir.setReturnValue(resolveNarrowAtgmHits(
                    candidates, pProjectile, pStartVec, pEndVec, pBoundingBox, pInflationAmount,
                    pStartVec.distanceToSqr(pEndVec), false
            ));
            return;
        }

        for (var entity : candidates) {
            Vector3d startVec = OBB.vec3ToVector3d(pStartVec);
            if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                if (pProjectile instanceof Projectile projectile &&
                        (projectile.getOwner() == entity || entity.getPassengers().contains(projectile.getOwner()))) {
                    continue;
                }
                var obbList = obbEntity.getOBBs();
                for (var obb : obbList) {
                    obb = obb.inflate(entity.getPickRadius() * 2);
                    Optional<Vector3d> optional = obb.clip(OBB.vec3ToVector3d(pStartVec), OBB.vec3ToVector3d(pEndVec));
                    double pDistance = pStartVec.distanceToSqr(pEndVec);
                    if (obb.contains(pStartVec)) {
                        if (pDistance >= 0) {
                            EntityHitResult hitResult = new EntityHitResult(entity, OBB.vector3dToVec3(optional.orElse(startVec)));
                            var acc = OBBHitter.getInstance(pProjectile);
                            acc.sbw$setCurrentHitPart(obb.part);
                            cir.setReturnValue(hitResult);
                            if (pLevel instanceof ServerLevel serverLevel && pProjectile.getDeltaMovement().lengthSqr() > 0.01 && pProjectile instanceof Projectile) {
                                Vec3 hitPos = hitResult.getLocation();
                                pLevel.playSound(null, BlockPos.containing(hitPos), ModSounds.HIT.get(), SoundSource.PLAYERS, 1, 1);
                                sendParticle(serverLevel, ModParticleTypes.FIRE_STAR.get(), hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.2, false);
                                sendParticle(serverLevel, ParticleTypes.SMOKE, hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.01, false);
                            }
                            return;
                        }
                    } else if (optional.isPresent()) {
                        var vec = new Vector3d(optional.get());
                        double d1 = pStartVec.distanceToSqr(OBB.vector3dToVec3(vec));
                        if (d1 < pDistance || pDistance == 0) {
                            EntityHitResult hitResult = new EntityHitResult(entity, OBB.vector3dToVec3(vec));
                            var acc = OBBHitter.getInstance(pProjectile);
                            acc.sbw$setCurrentHitPart(obb.part);
                            cir.setReturnValue(hitResult);
                            if (pLevel instanceof ServerLevel serverLevel && pProjectile.getDeltaMovement().lengthSqr() > 0.01 && pProjectile instanceof Projectile) {
                                Vec3 hitPos = hitResult.getLocation();
                                pLevel.playSound(null, BlockPos.containing(hitPos), ModSounds.HIT.get(), SoundSource.PLAYERS, 1, 1);
                                sendParticle(serverLevel, ModParticleTypes.FIRE_STAR.get(), hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.2, false);
                                sendParticle(serverLevel, ParticleTypes.SMOKE, hitPos.x, hitPos.y, hitPos.z, 2, 0, 0, 0, 0.01, false);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }

    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void getEntityHitResult(Entity pShooter, Vec3 pStartVec, Vec3 pEndVec, AABB pBoundingBox, Predicate<Entity> pFilter, double pDistance, CallbackInfoReturnable<EntityHitResult> cir) {
        Level level = pShooter.level();
        var entities = level.getEntities(pShooter, pBoundingBox.inflate(8), pFilter);
        if (containsNarrowAtgm(entities, pShooter)) {
            cir.setReturnValue(resolveNarrowAtgmHits(
                    entities, pShooter, pStartVec, pEndVec, pBoundingBox, 0.0f, pDistance, true
            ));
            return;
        }
        Vector3d startVec = OBB.vec3ToVector3d(pStartVec);

        for (Entity entity : entities) {
            if (!(entity instanceof OBBEntity obbEntity) || obbEntity.enableAABB()) {
                continue;
            }

            if (entity.getPassengers().contains(pShooter)) {
                continue;
            }

            var obbList = obbEntity.getOBBs();
            for (var obb : obbList) {
                obb = obb.inflate(entity.getPickRadius() * 2);
                Optional<Vector3d> optional = obb.clip(OBB.vec3ToVector3d(pStartVec), OBB.vec3ToVector3d(pEndVec));
                if (obb.contains(pStartVec)) {
                    if (pDistance >= 0) {
                        cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(optional.orElse(startVec))));
                        return;
                    }
                } else if (optional.isPresent()) {
                    var vec = new Vector3d(optional.get());
                    double d1 = pStartVec.distanceToSqr(OBB.vector3dToVec3(vec));
                    if (d1 < pDistance || pDistance == 0) {
                        if (entity.getRootVehicle() == pShooter.getRootVehicle() && !entity.canRiderInteract()) {
                            if (pDistance == 0) {
                                cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(vec)));
                                return;
                            }
                        } else {
                            cir.setReturnValue(new EntityHitResult(entity, OBB.vector3dToVec3(vec)));
                            return;
                        }
                    }
                }
            }
        }
    }

    private static boolean containsNarrowAtgm(Iterable<Entity> entities, Entity shooter) {
        if (!(shooter instanceof Projectile) || shooter instanceof WireGuideMissileEntity) {
            return false;
        }
        for (Entity entity : entities) {
            if (entity instanceof WireGuideMissileEntity) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replays the two vanilla hit-result overloads only when a guided missile is present.  This
     * keeps every non-ATGM path byte-for-byte on the existing mixin/vanilla route while preventing
     * the later vanilla AABB fallback from broadening the guided target again.
     */
    private static EntityHitResult resolveNarrowAtgmHits(
            Iterable<Entity> entities,
            Entity shooter,
            Vec3 start,
            Vec3 end,
            AABB queryBounds,
            float inflation,
            double maxDistance,
            boolean shooterOverload
    ) {
        double segmentDistance = start.distanceToSqr(end);
        double bestDistance = maxDistance == 0.0 ? Double.MAX_VALUE : maxDistance;
        Entity bestEntity = null;
        Vec3 bestPoint = null;

        for (Entity entity : entities) {
            Vec3 hitPoint = null;

            if (isNarrowAtgm(entity, shooter)) {
                // No target AABB/pick-radius inflation is allowed for guided missiles.
                hitPoint = WireGuideMissileEntity.preciseInterceptionHitPoint(
                        (WireGuideMissileEntity) entity, start, end
                );
            } else if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                if (!shooterOverload && shooter instanceof Projectile projectile &&
                        (projectile.getOwner() == entity || entity.getPassengers().contains(projectile.getOwner()))) {
                    continue;
                }
                if (shooterOverload && entity.getPassengers().contains(shooter)) {
                    continue;
                }

                for (var obb : obbEntity.getOBBs()) {
                    obb = obb.inflate(entity.getPickRadius() * 2);
                    Optional<Vector3d> optional = obb.clip(
                            OBB.vec3ToVector3d(start), OBB.vec3ToVector3d(end)
                    );
                    double pDistance = start.distanceToSqr(end);
                    if (obb.contains(start)) {
                        if (pDistance >= 0.0) {
                            EntityHitResult hitResult = new EntityHitResult(
                                    entity, OBB.vector3dToVec3(optional.orElse(OBB.vec3ToVector3d(start)))
                            );
                            OBBHitter.getInstance(shooter).sbw$setCurrentHitPart(obb.part);
                            emitObbHitEffects(shooter, hitResult);
                            return hitResult;
                        }
                    } else if (optional.isPresent()) {
                        Vec3 point = OBB.vector3dToVec3(optional.get());
                        double distance = start.distanceToSqr(point);
                        if (distance < pDistance || pDistance == 0.0) {
                            if (shooterOverload && entity.getRootVehicle() == shooter.getRootVehicle()
                                    && !entity.canRiderInteract() && pDistance != 0.0) {
                                continue;
                            }
                            EntityHitResult hitResult = new EntityHitResult(entity, point);
                            OBBHitter.getInstance(shooter).sbw$setCurrentHitPart(obb.part);
                            emitObbHitEffects(shooter, hitResult);
                            return hitResult;
                        }
                    }
                }
            } else {
                // The broad candidate query is retained, but non-OBB fallback candidates must
                // still belong to the original vanilla query bounds before clipping.
                if (!entity.getBoundingBox().intersects(queryBounds)) {
                    continue;
                }
                hitPoint = entity.getBoundingBox().inflate(inflation).clip(start, end).orElse(null);
            }

            if (hitPoint == null) {
                continue;
            }
            double distance = start.distanceToSqr(hitPoint);
            double allowed = maxDistance == 0.0 ? Double.MAX_VALUE : maxDistance;
            if (distance <= allowed && (bestEntity == null || distance < bestDistance)) {
                bestEntity = entity;
                bestPoint = hitPoint;
                bestDistance = distance;
            }
        }
        return bestEntity == null ? null : new EntityHitResult(bestEntity, bestPoint);
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
