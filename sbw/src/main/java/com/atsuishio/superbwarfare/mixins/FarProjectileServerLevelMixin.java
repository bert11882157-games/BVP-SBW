package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.FarProjectileSimulation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class FarProjectileServerLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void sbw$projectileTickBoundary(Entity entity, CallbackInfo callback) {
        if (!FarProjectileSimulation.beforeEntityTick((ServerLevel) (Object) this, entity)) callback.cancel();
    }
}
