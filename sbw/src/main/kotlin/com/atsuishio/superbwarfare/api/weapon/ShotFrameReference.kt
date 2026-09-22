package com.atsuishio.superbwarfare.api.weapon

/**
 * Stable provenance for the vehicle frame selected before a shot mutates ammo or fire-index state.
 * World-space vectors remain authoritative for ballistics and as the presentation fallback.
 */
data class ShotFrameReference(
    val weaponName: String,
    val positionSlot: Int?,
    val directionSlot: Int?,
    val muzzlePositionAttachment: String?,
    val muzzleDirectionAttachment: String?,
    val effectPositionAttachment: String?,
    val effectDirectionAttachment: String?,
    val sourceTransformSequence: Int,
    val sourceTransformServerTick: Long,
    val serverGameTime: Long,
)
