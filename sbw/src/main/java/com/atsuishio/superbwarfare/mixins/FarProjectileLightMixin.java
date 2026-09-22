package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.api.projectile.FarProjectileAccess;
import com.atsuishio.superbwarfare.client.FarTerrainClient;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Base sampling only: renderer overrides for emissive tracers still take precedence. */
@Mixin(EntityRenderer.class)
public abstract class FarProjectileLightMixin {
    @Inject(method = "getSkyLightLevel", at = @At("HEAD"), cancellable = true)
    private void sbw$farSky(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> callback) {
        if (entity instanceof FarProjectileAccess) {
            Integer light = FarTerrainClient.lightOverride(LightLayer.SKY, pos);
            if (light != null) callback.setReturnValue(light);
        }
    }

    @Inject(method = "getBlockLightLevel", at = @At("HEAD"), cancellable = true)
    private void sbw$farBlock(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> callback) {
        if (entity instanceof FarProjectileAccess) {
            Integer light = FarTerrainClient.lightOverride(LightLayer.BLOCK, pos);
            if (light != null) callback.setReturnValue(entity.isOnFire() ? 15 : light);
        }
    }
}
