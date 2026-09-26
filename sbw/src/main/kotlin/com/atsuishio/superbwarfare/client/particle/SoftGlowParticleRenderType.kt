package com.atsuishio.superbwarfare.client.particle

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureManager

/**
 * Self-lit fire (explosion fire clouds): added light, no depth write. Vanilla's lit sheet draws these as alpha-cut
 * cards that write depth, so each fire cloud hid the ones behind it and every blast fireball drawn after particles
 * with a hard edge, and fading fire vanished in steps. Added light needs no sorting: overlapping fire just gets
 * brighter, as it does in reality.
 */
object SoftGlowParticleRenderType : ParticleRenderType {
    override fun begin(builder: BufferBuilder, textureManager: TextureManager) {
        RenderSystem.depthMask(false)
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES)
        RenderSystem.enableBlend()
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
    }

    override fun end(tesselator: Tesselator) {
        tesselator.end()
        RenderSystem.defaultBlendFunc()
        RenderSystem.depthMask(true)
    }

    override fun toString() = "SUPERBWARFARE_SOFT_GLOW"
}
