package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.input.AircraftCollisionPicking;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

/** Client-only UI query, after camera mods have supplied their actual offset ray. */
@Mixin(value = ProjectileUtil.class, priority = 1100)
public class AircraftUiPickingMixin {
    @Inject(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"), cancellable = true)
    private static void sbw$pickPhysicalUiParts(Entity viewer, Vec3 start, Vec3 end, AABB bounds,
                                               Predicate<Entity> filter, double distanceSquared,
                                               CallbackInfoReturnable<EntityHitResult> cir) {
        if (AircraftCollisionPicking.isUiPickActive()) {
            cir.setReturnValue(AircraftCollisionPicking.pickInUiScope(viewer, start, end, bounds,
                    filter, distanceSquared));
        }
    }
}
