package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.tools.blast.BlastModel
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.TextureSheetParticle
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One element of an expanding blast shockwave shell. It moves radially along a fixed direction, is
 * translucent and full-bright, and is removed the moment its path touches a non-air collidable block.
 * Ticks and renders without allocating: position is written directly (no bounding box), culling and
 * per-frame light lookups are disabled.
 */
@OnlyIn(Dist.CLIENT)
class ShockwaveParticle(
    level: ClientLevel,
    private val centerX: Double,
    private val centerY: Double,
    private val centerZ: Double,
    private val dirX: Double,
    private val dirY: Double,
    private val dirZ: Double,
    private val fromRadius: Double,
    private val toRadius: Double,
    duration: Int,
    private val shellCount: Int,
    sprite: TextureAtlasSprite,
) : TextureSheetParticle(level, centerX + dirX * fromRadius, centerY + dirY * fromRadius, centerZ + dirZ * fromRadius) {

    init {
        setSprite(sprite)
        lifetime = max(1, duration)
        hasPhysics = false
        gravity = 0f
        friction = 1f
        xd = 0.0; yd = 0.0; zd = 0.0
        rCol = 0.92f; gCol = 0.93f; bCol = 0.96f
        alpha = BASE_ALPHA
        quadSize = sizeAt(fromRadius)
        // The spawn point itself may already be inside terrain (grazing directions).
        if (blocked(x, y, z)) remove()
    }

    override fun getRenderType(): ParticleRenderType = ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT

    override fun shouldCull(): Boolean = false

    override fun getLightColor(partialTick: Float): Int = FULL_BRIGHT

    override fun tick() {
        xo = x; yo = y; zo = z
        if (age++ >= lifetime) {
            remove()
            return
        }
        val progress = age.toDouble() / lifetime
        val radius = BlastModel.shockwaveRadiusAt(progress, fromRadius, toRadius)
        val nextX = centerX + dirX * radius
        val nextY = centerY + dirY * radius
        val nextZ = centerZ + dirZ * radius
        if (pathBlocked(nextX, nextY, nextZ)) {
            remove()
            return
        }
        x = nextX; y = nextY; z = nextZ
        quadSize = sizeAt(radius)
        val remaining = 1.0 - progress
        alpha = (BASE_ALPHA * sqrt(max(0.0, remaining))).toFloat()
    }

    /** Samples the step at <= half-block spacing so a fast front cannot skip a thin wall. */
    private fun pathBlocked(toX: Double, toY: Double, toZ: Double): Boolean {
        val dx = toX - x
        val dy = toY - y
        val dz = toZ - z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        val steps = max(1, ceil(length / STEP).toInt())
        for (i in 1..steps) {
            val f = i.toDouble() / steps
            if (blocked(x + dx * f, y + dy * f, z + dz * f)) return true
        }
        return false
    }

    private fun blocked(px: Double, py: Double, pz: Double): Boolean {
        val pos = CURSOR.set(px, py, pz)
        val state = level.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(level, pos).isEmpty
    }

    /** Neighbouring elements slightly overlap so the shell reads as one surface as it grows. */
    private fun sizeAt(radius: Double): Float {
        val spacing = sqrt(2.0 * PI * radius * radius / max(1, shellCount))
        return (0.6 * spacing).coerceIn(0.25, 12.0).toFloat()
    }

    companion object {
        private const val BASE_ALPHA = 0.35f
        private const val STEP = 0.5
        private const val FULL_BRIGHT = 0xF000F0
        /** Client render thread only; shared to keep block checks allocation-free. */
        private val CURSOR = BlockPos.MutableBlockPos()
    }
}
