package com.atsuishio.superbwarfare.client.particle

import com.atsuishio.superbwarfare.Mod
import net.minecraft.client.particle.ParticleProvider
import net.minecraft.client.particle.SpriteSet
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.particles.SimpleParticleType
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import kotlin.math.max

/**
 * Sprite sets of the TNT blast presentation. Particle sprites live in the particle engine's own atlas, which is
 * only reachable through registered sprite sets (Minecraft.getTextureAtlas does not know it). The sets are
 * rebound by the engine on every resource reload, so frames are read from them per blast, never cached.
 */
@OnlyIn(Dist.CLIENT)
object BlastSprites {
    const val FIREBALL_FRAMES = 16
    const val SOOT_FRAMES = 12

    @Volatile private var fireballSet: SpriteSet? = null
    @Volatile private var sootSet: SpriteSet? = null
    @Volatile private var shockwaveSet: SpriteSet? = null
    @Volatile private var warned = false

    private val NONE = ParticleProvider<SimpleParticleType> { _, _, _, _, _, _, _, _ -> null }

    fun fireball(set: SpriteSet): ParticleProvider<SimpleParticleType> { fireballSet = set; return NONE }
    fun soot(set: SpriteSet): ParticleProvider<SimpleParticleType> { sootSet = set; return NONE }
    fun shockwave(set: SpriteSet): ParticleProvider<SimpleParticleType> { shockwaveSet = set; return NONE }

    /** Fireball animation frames (white-hot flash to smoke), or null before the particle engine is ready. */
    fun fireballFrames(): Array<TextureAtlasSprite>? = frames(fireballSet, FIREBALL_FRAMES, "blast_fireball")

    fun sootFrames(): Array<TextureAtlasSprite>? = frames(sootSet, SOOT_FRAMES, "blast_soot")

    fun shockwaveSprite(): TextureAtlasSprite? = frames(shockwaveSet, 1, "blast_shockwave")?.get(0)

    private fun frames(set: SpriteSet?, count: Int, name: String): Array<TextureAtlasSprite>? {
        if (set == null) {
            if (!warned) {
                warned = true
                Mod.LOGGER.warn("TNT blast sprites '{}' are not registered; blast presentation skipped", name)
            }
            return null
        }
        // MutableSpriteSet.get(age, lifetime) = sprites[age * (size - 1) / lifetime]: index i of count frames.
        val lifetime = max(1, count - 1)
        return Array(count) { set.get(it, lifetime) }
    }
}
