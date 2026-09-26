package com.atsuishio.superbwarfare.client.particle

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Camera
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.*
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.util.Mth
import org.joml.Vector3f
import kotlin.math.*

/** One bounded, coherent foil bloom: eighteen tumbling ribbons, not eighteen simulated entities. */
class ChaffBurstParticle(level: ClientLevel, x: Double, y: Double, z: Double,
                         vx: Double, vy: Double, vz: Double, sprites: SpriteSet) : TextureSheetParticle(level, x, y, z) {
    private val phase = random.nextFloat() * (Math.PI * 2).toFloat()
    private val fan = Vector3f(vx.toFloat(), vy.toFloat(), vz.toFloat()).let {
        if (it.lengthSquared() < .0001f) it.set(1f, 0f, 0f) else it.normalize()
    }
    var strength = 1f
    init {
        lifetime = 32
        hasPhysics = false
        xd = vx; yd = vy; zd = vz
        friction = .91f
        gravity = .025f
        setSize(6f, 6f)
        pickSprite(sprites)
    }
    override fun getRenderType(): ParticleRenderType = SoftParticleRenderType
    override fun render(buffer: VertexConsumer, camera: Camera, partialTicks: Float) {
        val t = age + partialTicks
        val progress = (t / lifetime).coerceIn(0f, 1f)
        val spread = (1f - exp(-t * .12f)) * (1.1f + strength * .8f)
        val center = Vector3f((Mth.lerp(partialTicks.toDouble(), xo, x) - camera.position.x).toFloat(),
            (Mth.lerp(partialTicks.toDouble(), yo, y) - camera.position.y).toFloat(),
            (Mth.lerp(partialTicks.toDouble(), zo, z) - camera.position.z).toFloat())
        val fade = (1f - progress).pow(1.3f)
        // Sampling the bright centre makes clean foil strips; width, twist and glint vary smoothly.
        val u = (u0 + u1) * .5f; val v = (v0 + v1) * .5f
        repeat(18) { i ->
            val angle = phase + i * 2.399963f
            val radial = .25f + (i % 6) / 7f
            val offset = Vector3f(cos(angle) * spread * radial, sin(angle) * spread * radial,
                sin(angle * 1.7f) * spread * .55f).add(Vector3f(fan).mul(spread * .65f)).add(center)
            val tumble = phase + i + t * (.13f + (i % 4) * .035f)
            val glint = .45f + .55f * abs(sin(tumble * 1.7f))
            val width = .018f + .035f * abs(cos(tumble))
            val length = .16f + (i % 5) * .045f
            val rotation = org.joml.Quaternionf(camera.rotation()).rotateZ(tumble)
            for ((sx, sy) in CORNERS) {
                val point = Vector3f(sx * width, sy * length, 0f).rotate(rotation).add(offset)
                buffer.vertex(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()).uv(u, v)
                    .color(.78f * glint, .88f * glint, glint, fade).uv2(LightTexture.FULL_BRIGHT).endVertex()
            }
        }
    }
    class Provider(private val sprites: SpriteSet) : ParticleProvider<SimpleParticleType> {
        override fun createParticle(type: SimpleParticleType, level: ClientLevel, x: Double, y: Double, z: Double,
                                    vx: Double, vy: Double, vz: Double): Particle =
            ChaffBurstParticle(level, x, y, z, vx, vy, vz, sprites)
    }
    companion object {
        private val CORNERS = arrayOf(-1f to -1f, -1f to 1f, 1f to 1f, 1f to -1f)
    }
}
