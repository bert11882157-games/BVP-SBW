package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.network.message.receive.ShockwaveMessage
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.Random

/**
 * Expands a TNT blast shockwave as a translucent hemispherical shell of [ShockwaveParticle]s from the fireball
 * to the moderate damage radius. Each shockwave is capped by its message budget; all live shockwaves together
 * are capped by [GLOBAL_BUDGET] so overlapping heavy bombs cannot flood the particle engine.
 */
@OnlyIn(Dist.CLIENT)
object ShockwaveClient {
    private const val GLOBAL_BUDGET = 3000

    private var trackedLevel: Any? = null
    /** Parallel arrays of (expiry game tick, particle count) for shockwaves still expanding. */
    private val activeExpiry = LongArray(64)
    private val activeCount = IntArray(64)
    private var activeSize = 0

    @JvmStatic
    fun emit(message: ShockwaveMessage) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (!message.valid()) return
        val now = level.gameTime
        if (trackedLevel !== level) {
            trackedLevel = level
            activeSize = 0
        }
        var live = 0
        var write = 0
        for (read in 0 until activeSize) {
            if (activeExpiry[read] > now) {
                activeExpiry[write] = activeExpiry[read]
                activeCount[write] = activeCount[read]
                live += activeCount[write]
                write++
            }
        }
        activeSize = write
        if (activeSize >= activeExpiry.size) return

        val quality = when (mc.options.particles().get()) {
            ParticleStatus.ALL -> 1.0
            ParticleStatus.DECREASED -> 0.5
            else -> 0.2
        }
        val budget = minOf((message.maxParticles * quality).toInt(), GLOBAL_BUDGET - live)
        val count = BlastModel.shockwaveParticleCount(message.toRadius.toDouble(), budget)
        if (count <= 0) return

        val sprite = BlastSprites.shockwaveSprite() ?: return
        val phase = Random(message.seed).nextDouble() * Math.PI * 2.0
        val direction = DoubleArray(3)
        val center = message.position
        for (index in 0 until count) {
            BlastModel.hemisphereDirection(index, count, phase, direction)
            mc.particleEngine.add(ShockwaveParticle(level, center.x, center.y, center.z,
                direction[0], direction[1], direction[2], message.fromRadius.toDouble(), message.toRadius.toDouble(),
                message.durationTicks, count, sprite))
        }
        activeExpiry[activeSize] = now + message.durationTicks + 1
        activeCount[activeSize] = count
        activeSize++
        com.atsuishio.superbwarfare.Mod.LOGGER.info("TNT shockwave presented: {} -> {} m, {} particles, {} ticks",
            "%.1f".format(message.fromRadius), "%.1f".format(message.toRadius), count, message.durationTicks)
    }
}
