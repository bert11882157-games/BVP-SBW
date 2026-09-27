package com.atsuishio.superbwarfare.mixins;

import com.atsuishio.superbwarfare.client.FarEffectsClient;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class FarParticleEngineMixin {
    @Inject(method = "add", at = @At("HEAD"))
    private void sbw$registerFarParticle(Particle particle, CallbackInfo callback) { FarEffectsClient.deferParticle(particle); }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void sbw$deferFarParticle(Particle particle, VertexConsumer vertices, Camera camera, float partialTick) {
        if (com.atsuishio.superbwarfare.diagnostics.ParticleProbe.counting) com.atsuishio.superbwarfare.diagnostics.ParticleProbe.count(particle);
        if (com.atsuishio.superbwarfare.diagnostics.ParticleProbe.hideParticles) return;
        if (!FarEffectsClient.deferParticle(particle)) particle.render(vertices, camera, partialTick);
    }
}
