package com.atsuishio.superbwarfare.client.particle

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos

/** Missing terrain is not darkness. Remember real lighting without requesting distant chunks. */
class DistantParticleLight {
    private var previous: Int? = null

    fun sample(level: ClientLevel, x: Double, y: Double, z: Double): Int {
        val position = BlockPos.containing(x, y, z)
        if (level.isLoaded(position)) previous = LevelRenderer.getLightColor(level, position)
        // Skylight still follows the client's day/night lightmap; smoke is not made emissive.
        return previous ?: if (level.dimensionType().hasSkyLight()) LightTexture.pack(0, 15)
            else LightTexture.pack(8, 0)
    }
}
