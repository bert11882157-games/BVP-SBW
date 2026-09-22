package com.atsuishio.superbwarfare.client.renderer.vehicle

import net.minecraft.core.BlockPos

/** Null preserves native sampling; only aliased, outside-world block Y needs an override. */
internal object VehicleLightSampling {
    fun skyLightOverride(
        probeY: Int,
        minBuildHeight: Int,
        maxBuildHeight: Int,
        hasSkyLight: Boolean,
    ): Int? {
        if (!requiresOverride(probeY, minBuildHeight, maxBuildHeight)) return null
        return if (probeY >= maxBuildHeight && hasSkyLight) 15 else 0
    }

    fun blockLightOverride(
        probeY: Int,
        minBuildHeight: Int,
        maxBuildHeight: Int,
        onFire: Boolean,
    ): Int? {
        if (!requiresOverride(probeY, minBuildHeight, maxBuildHeight)) return null
        return if (onFire) 15 else 0
    }

    private fun requiresOverride(probeY: Int, minBuildHeight: Int, maxBuildHeight: Int): Boolean {
        if (minBuildHeight >= maxBuildHeight) return false
        if (probeY >= minBuildHeight && probeY < maxBuildHeight) return false
        // LightEngine packs BlockPos before sampling; preserve every round-trippable Y.
        return BlockPos.getY(BlockPos.asLong(0, probeY, 0)) != probeY
    }
}
