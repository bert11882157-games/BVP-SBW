package com.atsuishio.superbwarfare.api.aircraft

/**
 * Which end of a store's authored model is its nose, in model-file coordinates.
 *
 * Aircraft models place the nose at model -Z, and suspended stores are drawn in that same frame
 * about their mount point. A store authored with its nose at +Z must be turned 180 degrees
 * about the vertical axis through the mount point so it does not hang tail-first. The key only
 * affects presentation; launch positions, offsets and projectile profiles are unchanged.
 */
object AircraftStoreModelForward {
    const val KEY = "ModelForward"
    const val NEGATIVE_Z = "-Z"
    const val POSITIVE_Z = "+Z"
    /** Omitting the key keeps the established convention. */
    const val DEFAULT = NEGATIVE_Z
    val values = setOf(NEGATIVE_Z, POSITIVE_Z)

    /** Degrees about the model's vertical axis that turn its nose onto model -Z. */
    fun mountYawDegrees(modelForward: String): Float = if (modelForward == POSITIVE_Z) 180f else 0f
}
