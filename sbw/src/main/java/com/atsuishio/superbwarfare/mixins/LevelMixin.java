package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.ProjectileObbTargets;
import com.atsuishio.superbwarfare.entity.vehicle.base.AircraftCollisionIndex;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"))
    public void getEntities(Entity pEntity, AABB pBoundingBox, Predicate<? super Entity> pPredicate, CallbackInfoReturnable<List<Entity>> cir) {
        if (!(pEntity instanceof Projectile)) {
            for (Entity aircraft : AircraftCollisionIndex.query((Level) (Object) this, pBoundingBox)) {
                if (aircraft != pEntity && pPredicate.test(aircraft) && !cir.getReturnValue().contains(aircraft)) {
                    cir.getReturnValue().add(aircraft);
                }
            }
            return;
        }

        Level level = (Level) (Object) this;
        List<Entity> result = cir.getReturnValue();
        for (Entity aircraft : com.atsuishio.superbwarfare.entity.vehicle.base.AircraftSurfaceProjectileIndex.query(level, pBoundingBox)) {
            if (aircraft != pEntity && pPredicate.test(aircraft) && !result.contains(aircraft)) result.add(aircraft);
        }
        // Only registered OBB owners can add a candidate here; never scan the whole level per projectile.
        boolean server = !level.isClientSide;
        for (ProjectileObbTargets.Target target : ProjectileObbTargets.candidates(level)) {
            Entity entity = target.getEntity();
            if (entity == pEntity || entity instanceof Projectile || entity.isRemoved() || entity.level() != level) continue;
            if (!target.mayOverlap(pBoundingBox)) continue;
            if (server && entity instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle
                    && !vehicle.computed().getAircraftSurfaceModules().isEmpty()) continue;
            if (!pPredicate.test(entity)) continue;
            if (target.collides(pBoundingBox) && !result.contains(entity)) result.add(entity);
        }
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("RETURN"))
    private <T extends Entity> void sbw$physicalAircraftCandidates(EntityTypeTest<Entity, T> type,
            AABB bounds, Predicate<? super T> predicate, List<? super T> result, int limit, CallbackInfo ci) {
        if (result.size() >= limit) return;
        for (Entity aircraft : AircraftCollisionIndex.query((Level) (Object) this, bounds)) {
            T candidate = type.tryCast(aircraft);
            if (candidate != null && predicate.test(candidate) && !result.contains(candidate)) {
                result.add(candidate);
                if (result.size() >= limit) return;
            }
        }
    }
}
