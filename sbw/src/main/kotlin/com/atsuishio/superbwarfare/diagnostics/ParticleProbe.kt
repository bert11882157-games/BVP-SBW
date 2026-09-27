package com.atsuishio.superbwarfare.diagnostics

import net.minecraft.client.particle.Particle

/**
 * Perf-probe switches for the particle pass: [hideParticles] skips drawing ordinary particles and [hideBlasts] skips
 * the BlastEffects fireball/smoke quads (A/B of their frame cost), [counting] tallies drawn particles per class.
 * Only the diagnostics perf probe sets these; every flag is off in a normal game.
 */
object ParticleProbe {
    @JvmField @Volatile var hideParticles = false
    @JvmField @Volatile var hideBlasts = false
    @JvmField @Volatile var counting = false
    private val counts = HashMap<Class<*>, IntArray>()

    @JvmStatic
    fun count(particle: Particle) {
        counts.getOrPut(particle.javaClass) { IntArray(1) }[0]++
    }

    fun start() {
        counts.clear(); counting = true
    }

    /** Drawn particles per frame by class, largest first (top 12). */
    fun finish(frames: Int): Map<String, Double> {
        counting = false
        val out = linkedMapOf<String, Double>()
        counts.entries.sortedByDescending { it.value[0] }.take(12).forEach {
            out[it.key.simpleName.ifEmpty { it.key.name }] = if (frames == 0) 0.0 else it.value[0].toDouble() / frames
        }
        counts.clear()
        return out
    }
}
