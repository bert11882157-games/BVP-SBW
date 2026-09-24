package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3

/**
 * The point of a store's authored model that touches its pylon, in model-file blocks
 * (model pixels / 16), before [AircraftStoreModelForward] turning and before `Scale`.
 *
 * Pack generators place it on top of the store body (suspension lugs included) at the body's
 * lengthwise centre, so every store hangs from its mount point instead of straddling it.
 * Mount points are therefore authored at the pylon's lower attachment surface. The key only
 * moves presentation; launch positions follow `LaunchOffset`, which packs author to match.
 */
object AircraftStoreMountAnchor {
    const val KEY = "MountAnchor"
    /** Keeps an authored anchor within a store-sized volume around the model origin. */
    const val MAX_LENGTH_BLOCKS = 8.0

    fun validate(store: JsonObject) {
        val raw = store[KEY] ?: return
        val anchor = requireNotNull(AircraftArmamentRegistry.vector(raw)) { "$KEY must be a finite [x, y, z]" }
        require(store.has("Model")) { "$KEY describes an authored model" }
        require(anchor.length() <= MAX_LENGTH_BLOCKS) { "$KEY is outside the store model" }
    }

    fun read(store: JsonObject): Vec3 = store[KEY]?.let { AircraftArmamentRegistry.vector(it) } ?: Vec3.ZERO
}
