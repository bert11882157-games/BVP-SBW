package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.tools.blast.BlastModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import kotlin.math.max
import kotlin.math.min

/**
 * One puff of a TNT fireball. [Kind.FIRE] grows from the blast centre to its place inside the ball within the
 * first fifth of its life, glowing white-hot to orange to red, then darkens and rises. [Kind.SOOT] waits
 * [delay] ticks (the fire phase) and then lingers as dark smoke over the burnt-out ball.
 * Position is written directly and culling is off, as for [ShockwaveParticle].
 */
@OnlyIn(Dist.CLIENT)
class FireballParticle(
    level: ClientLevel,
    private val centerX: Double,
    private val centerY: Double,
    private val centerZ: Double,
    private val offsetX: Double,
    private val offsetY: Double,
    private val offsetZ: Double,
    private val halfSize: Float,
    life: Int,
    /** Upward drift (blocks per tick) reached at the end of life. */
    private val rise: Double,
    private val kind: Kind,
    private val delay: Int,
    private val frames: Array<TextureAtlasSprite>,
) : TextureSheetParticle(level, centerX, centerY, centerZ) {
    enum class Kind { FIRE, SOOT }

    private val color = FloatArray(4)
    private var lift = 0.0
    private var glowing = true

    init {
        lifetime = max(1, life + delay)
        hasPhysics = false
        gravity = 0f
        friction = 1f
        xd = 0.0; yd = 0.0; zd = 0.0
        apply()
        xo = x; yo = y; zo = z
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun shouldCull(): Boolean = false

    override fun getLightColor(partialTick: Float): Int =
        if (kind == Kind.FIRE && glowing) FULL_BRIGHT else super.getLightColor(partialTick)

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        apply()
    }

    private fun apply() {
        if (kind == Kind.FIRE) applyFire() else applySoot()
    }

    private fun applyFire() {
        val progress = age.toDouble() / lifetime
        val expansion = BlastModel.fireballExpansionAt(progress)
        if (progress > RISE_START) lift += rise * (progress - RISE_START) / (1.0 - RISE_START)
        x = centerX + offsetX * expansion
        y = centerY + offsetY * expansion + lift
        z = centerZ + offsetZ * expansion
        quadSize = (halfSize * (0.3 + 0.7 * expansion)).toFloat()
        BlastModel.fireballColorAt(progress, color)
        rCol = color[0]; gCol = color[1]; bCol = color[2]; alpha = color[3]
        glowing = BlastModel.fireballGlowing(progress)
        setSprite(frames[min(frames.size - 1, (progress * frames.size).toInt())])
    }

    private fun applySoot() {
        val active = age - delay
        x = centerX + offsetX
        z = centerZ + offsetZ
        if (active < 0) {
            y = centerY + offsetY
            alpha = 0f
            quadSize = halfSize
            setSprite(frames[0])
            return
        }
        val span = max(1, lifetime - delay)
        val progress = active.toDouble() / span
        lift += rise * (1.0 - 0.6 * progress)
        y = centerY + offsetY + lift
        quadSize = (halfSize * (1.0 + 0.6 * progress)).toFloat()
        val fadeIn = min(1.0, active / SOOT_FADE_IN_TICKS)
        alpha = (SOOT_ALPHA * fadeIn * (1.0 - progress)).toFloat()
        rCol = SOOT_GREY; gCol = SOOT_GREY * 0.92f; bCol = SOOT_GREY * 0.86f
        setSprite(frames[min(frames.size - 1, (progress * frames.size).toInt())])
    }

    companion object {
        private const val FULL_BRIGHT = 0xF000F0
        private const val RISE_START = 0.35
        private const val SOOT_FADE_IN_TICKS = 6.0
        private const val SOOT_ALPHA = 0.8
        private const val SOOT_GREY = 0.2f
    }
}
