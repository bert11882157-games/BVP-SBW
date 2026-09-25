package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.network.message.receive.FireballMessage
import com.atsuishio.superbwarfare.tools.blast.BlastModel
import net.minecraft.client.Minecraft
import net.minecraft.client.ParticleStatus
import net.minecraft.core.BlockPos
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.Random
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Builds the visible fireball of a TNT blast: glowing puffs filling a ball whose edge is the fireball radius
 * (half-ball for a ground burst), followed by lingering soot for charges big enough to leave a cloud.
 * Live fireball particles are capped by [GLOBAL_BUDGET] so autocannon fire and carpet bombing stay bounded.
 */
@OnlyIn(Dist.CLIENT)
object FireballClient {
    private const val GLOBAL_BUDGET = 1600
    private const val SOOT_MIN_RADIUS = 0.45
    private const val LOG_INTERVAL_MS = 5000L
    private var lastLogAt = 0L

    private var trackedLevel: Any? = null
    private val activeExpiry = LongArray(256)
    private val activeCount = IntArray(256)
    private var activeSize = 0

    @JvmStatic
    fun emit(message: FireballMessage) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (!message.valid()) return
        val now = level.gameTime
        val live = liveParticles(level, now)
        if (activeSize >= activeExpiry.size) return

        val quality = when (mc.options.particles().get()) {
            ParticleStatus.ALL -> 1.0
            ParticleStatus.DECREASED -> 0.6
            else -> 0.3
        }
        val radius = message.radius.toDouble()
        val available = GLOBAL_BUDGET - live
        val fire = BlastModel.fireballPuffCount(radius, max(2, (48 * quality).toInt()).coerceAtMost(available))
        if (fire <= 0) return
        val soot = if (radius >= SOOT_MIN_RADIUS) (fire / 2).coerceAtMost(available - fire).coerceAtLeast(0) else 0

        val fireFrames = BlastSprites.fireballFrames() ?: return
        val sootFrames = BlastSprites.sootFrames() ?: fireFrames

        val center = message.position
        val groundBurst = solidBelow(level, center.x, center.y, center.z)
        val random = Random(message.seed)
        val half = BlastModel.fireballPuffHalfSize(radius, fire)
        val reach = BlastModel.fireballPuffReach(radius, half)
        val life = BlastModel.fireballLifetimeTicks(radius)
        val rise = 0.01 + 0.03 * radius
        val offset = DoubleArray(3)
        var longest = 0
        for (index in 0 until fire + soot) {
            BlastModel.fireballPuffOffset(random.nextDouble(), random.nextDouble(),
                0.04 + 0.96 * random.nextDouble(), groundBurst, offset)
            val jitter = 0.85 + 0.3 * random.nextDouble()
            if (index < fire) {
                val puffLife = max(4, (life * (0.8 + 0.4 * random.nextDouble())).toInt())
                longest = max(longest, puffLife)
                mc.particleEngine.add(FireballParticle(level, center.x, center.y, center.z,
                    offset[0] * reach, offset[1] * reach, offset[2] * reach, (half * jitter).toFloat(), puffLife,
                    rise, FireballParticle.Kind.FIRE, 0, fireFrames))
            } else {
                val delay = (life * (0.5 + 0.2 * random.nextDouble())).toInt()
                val sootLife = (30 + 18 * sqrt(radius) * (0.8 + 0.4 * random.nextDouble())).toInt()
                longest = max(longest, delay + sootLife)
                mc.particleEngine.add(FireballParticle(level, center.x, center.y, center.z,
                    offset[0] * reach * 0.8, offset[1] * reach * 0.8 + 0.25 * radius, offset[2] * reach * 0.8,
                    (half * 1.1 * jitter).toFloat(), sootLife, 0.012 + 0.012 * radius,
                    FireballParticle.Kind.SOOT, delay, sootFrames))
            }
        }
        activeExpiry[activeSize] = now + longest + 1
        activeCount[activeSize] = fire + soot
        activeSize++
        val clock = System.currentTimeMillis()
        if (clock - lastLogAt >= LOG_INTERVAL_MS) {
            lastLogAt = clock
            com.atsuishio.superbwarfare.Mod.LOGGER.info("TNT fireball presented: radius {} m, {} fire + {} soot puffs, {} ticks{}",
                "%.2f".format(radius), fire, soot, longest, if (groundBurst) ", ground burst" else "")
        }
    }

    /** Compacts expired entries and returns the particles still alive. */
    private fun liveParticles(level: Any, now: Long): Int {
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
        return live
    }

    private fun solidBelow(level: net.minecraft.client.multiplayer.ClientLevel, x: Double, y: Double, z: Double): Boolean {
        val pos = BlockPos.containing(x, y - 0.5, z)
        val state = level.getBlockState(pos)
        return !state.isAir && !state.getCollisionShape(level, pos).isEmpty
    }
}
