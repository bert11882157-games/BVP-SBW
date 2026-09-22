package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.nbt.CompoundTag

/** Fresh command spawns omit health; saved zeroes and explicit damage flags remain authoritative. */
internal fun componentHealthOrDefault(
    tag: CompoundTag,
    healthKey: String,
    damagedKey: String,
    maximum: Float,
): Float = when {
    tag.contains(healthKey) -> tag.getFloat(healthKey)
    tag.getBoolean(damagedKey) -> 0F
    else -> maximum
}
