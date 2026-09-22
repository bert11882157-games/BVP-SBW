package com.yourname.berts_vehicle_pack.client.particle;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;

/** TaP's blended, depth-tested quads: no depth writes and an alpha cutoff of 0.001. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BvpTapParticleMaterial implements ParticleRenderType {
    public static final BvpTapParticleMaterial INSTANCE = new BvpTapParticleMaterial();
    private static ShaderInstance shader;
    private BvpTapParticleMaterial() {}

    @SubscribeEvent
    public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                new ResourceLocation(BertsVehiclePack.MODID, "tap_exhaust"), DefaultVertexFormat.f_85813_),
                loaded -> shader = loaded);
    }

    @Override public void m_6505_(BufferBuilder buffer, TextureManager textures) {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, TextureAtlas.f_118260_);
        buffer.m_166779_(VertexFormat.Mode.QUADS, DefaultVertexFormat.f_85813_);
    }

    @Override public void m_6294_(Tesselator tessellator) {
        tessellator.m_85914_();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    @Override public String toString() { return "BVP_TAP_EXHAUST"; }
}
