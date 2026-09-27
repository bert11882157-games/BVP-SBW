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

    /**
     * Vanilla's translucent and lit particle sheets write depth for every drawn texel. Fireballs, afterburner plumes
     * and missile motors are drawn after the particles, so any smoke puff, dust cloud or impact flash anywhere in
     * front of a fireball cut it out. These sheets now blend without writing depth (as SBW's own soft particles
     * already do); the fireball draws over the smoke. Opaque and terrain sheets are unchanged.
     */
    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleRenderType;begin(Lcom/mojang/blaze3d/vertex/BufferBuilder;Lnet/minecraft/client/renderer/texture/TextureManager;)V"))
    private void sbw$softSheets(net.minecraft.client.particle.ParticleRenderType type,
                                com.mojang.blaze3d.vertex.BufferBuilder builder,
                                net.minecraft.client.renderer.texture.TextureManager textures) {
        type.begin(builder, textures);
        if (type == net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                || type == net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_LIT)
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
    }

    @Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"))
    private void sbw$deferFarParticle(Particle particle, VertexConsumer vertices, Camera camera, float partialTick) {
        if (com.atsuishio.superbwarfare.diagnostics.ParticleProbe.counting) com.atsuishio.superbwarfare.diagnostics.ParticleProbe.count(particle);
        if (com.atsuishio.superbwarfare.diagnostics.ParticleProbe.hideParticles) return;
        if (!FarEffectsClient.deferParticle(particle)) particle.render(vertices, camera, partialTick);
    }
}
