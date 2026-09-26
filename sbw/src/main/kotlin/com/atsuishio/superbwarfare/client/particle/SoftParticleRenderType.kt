package com.atsuishio.superbwarfare.client.particle

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureManager

/**
 * Translucent particles that do not write depth. Vanilla's translucent sheet writes depth for every visible texel,
 * so a half-transparent smoke puff hides whatever translucent effect is drawn after it (the blast fireballs and
 * afterburner plumes draw after particles) and overlapping effects cut each other into squares.
 */
object SoftParticleRenderType : ParticleRenderType {
    override fun begin(builder: BufferBuilder, textureManager: TextureManager) {
        RenderSystem.depthMask(false)
        RenderSystem.setShaderTexture(0, TextureAtlas.LOCATION_PARTICLES)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE)
    }

    override fun end(tesselator: Tesselator) {
        tesselator.end()
        RenderSystem.depthMask(true)
    }

    override fun toString() = "SUPERBWARFARE_SOFT_TRANSLUCENT"
}
