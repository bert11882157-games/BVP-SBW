package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.FarTerrainClient;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The far pass calls the renderer directly after its terrain guard, so it is not intercepted here. */
@Mixin(EntityRenderDispatcher.class)
public abstract class FarTerrainEntityRenderMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void sbw$deferDistantVehicle(Entity entity, double x, double y, double z, float yaw,
                                        float partialTick, PoseStack pose, MultiBufferSource buffers,
                                        int light, CallbackInfo callback) {
        if (entity instanceof VehicleEntity vehicle && FarTerrainClient.deferNative(vehicle)) callback.cancel();
        if (com.atsuishio.superbwarfare.client.FarEffectsClient.deferProjectile(entity)) callback.cancel();
    }
}
