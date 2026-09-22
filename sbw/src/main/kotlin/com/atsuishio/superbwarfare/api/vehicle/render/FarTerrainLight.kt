package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.core.BlockPos
import net.minecraft.world.level.chunk.DataLayer

/** Match sparse skylight storage: absent sections inherit from above; explicit darkness is valid. */
object FarTerrainLight {
    fun sky(hasSky: Boolean, layers: Map<Int, DataLayer>, pos: BlockPos): Int {
        if (!hasSky) return 0
        val section = pos.y shr 4
        layers[section]?.let { return it.get(pos.x and 15, pos.y and 15, pos.z and 15) }
        val above = layers.keys.filter { it > section }.minOrNull() ?: return 15
        return layers.getValue(above).get(pos.x and 15, 0, pos.z and 15)
    }
}
