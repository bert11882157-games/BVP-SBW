package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.FarProjectileTracking;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public abstract class FarProjectileServerEntityMixin {
    @Shadow @Final private Entity entity;
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void sbw$noteNativeUpdate(CallbackInfo callback) { FarProjectileTracking.sent(entity); }

    @Inject(method = "addPairing", at = @At("TAIL"))
    private void sbw$pairSimulationState(ServerPlayer player, CallbackInfo callback) {
        FarProjectileTracking.paired(entity, player);
    }
}
