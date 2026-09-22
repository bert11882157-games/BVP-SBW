package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.entity.OBBEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.AircraftCollisionIndex;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

@Mixin(Level.class)
public abstract class LevelMixin {

    @Shadow
    protected abstract LevelEntityGetter<Entity> getEntities();

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

        for (Entity aircraft : com.atsuishio.superbwarfare.entity.vehicle.base.AircraftSurfaceProjectileIndex.query((Level) (Object) this, pBoundingBox)) {
            if (aircraft != pEntity && pPredicate.test(aircraft) && !cir.getReturnValue().contains(aircraft)) cir.getReturnValue().add(aircraft);
        }
        StreamSupport.stream(this.getEntities().getAll().spliterator(), false).filter(e -> pPredicate.test(e) && e != pEntity)
                .forEach(entity -> {
                            if (!((Level) (Object) this).isClientSide
                                    && entity instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle
                                    && !vehicle.computed().getAircraftSurfaceModules().isEmpty()) return;
                            if (entity instanceof OBBEntity obbEntity && !obbEntity.enableAABB()) {
                                for (OBB obb : obbEntity.getOBBs()) {
                                    if (OBB.isColliding(obb, pBoundingBox) && !cir.getReturnValue().contains(entity)) {
                                        cir.getReturnValue().add(entity);
                                    }
                                }
                            }
                        }
                );
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
