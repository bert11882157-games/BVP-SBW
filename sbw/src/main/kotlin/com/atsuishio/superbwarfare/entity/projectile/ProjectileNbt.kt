package com.atsuishio.superbwarfare.entity.projectile

import net.minecraft.nbt.CompoundTag

internal inline fun CompoundTag.readIfPresent(
    key: String,
    tagType: Int? = null,
    reader: CompoundTag.(String) -> Unit,
) {
    val present = if (tagType == null) contains(key) else contains(key, tagType)
    if (present) reader(key)
}
