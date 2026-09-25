package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.phys.Vec3

/**
 * The point of a store's authored model that touches its pylon, in model-file blocks
 * (model pixels / 16), before [AircraftStoreModelForward] turning and before `Scale`.
 *
 * It is the store's top anchor: the suspension lug top (the midpoint of the lug tops for several
 * lugs) or, for a lugless store, the top of its body at the station it hangs from. A bottom pylon
 * station (or the mount point) receives it; side stations receive the side anchors instead, and
 * `MountAxis` derives the launch point from the same placement. See [AircraftStoreAttachment].
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
