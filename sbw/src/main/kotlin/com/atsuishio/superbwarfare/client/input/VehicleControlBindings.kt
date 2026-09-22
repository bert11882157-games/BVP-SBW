package com.atsuishio.superbwarfare.client.input

import net.minecraft.client.KeyMapping

/** Registry and runtime priority policy for the dedicated in-vehicle control profile. */
object VehicleControlBindings {
    const val BVP_CATEGORY = "key.categories.berts_vehicle_pack"
    const val TACZ_CATEGORY = "key.category.tacz"
    const val LEGACY_SBW_CATEGORY = "key.categories.superbwarfare"

    private val mappings = mutableListOf<VehicleKeyMapping>()

    @JvmStatic
    fun register(mapping: VehicleKeyMapping): VehicleKeyMapping {
        mappings += mapping
        return mapping
    }

    @JvmStatic
    fun isActive(): Boolean = VehicleKeyConflictContext.isActive()

    @JvmStatic
    fun isVehicleMapping(mapping: KeyMapping): Boolean = mapping is VehicleKeyMapping

    /** The legacy SBW controls remain registered and saved, but are intentionally hidden. */
    @JvmStatic
    fun isVisibleInControls(mapping: KeyMapping): Boolean =
        mapping.category != LEGACY_SBW_CATEGORY

    /** Legacy SBW and the replacement BVP profile never create Controls-screen warnings. */
    @JvmStatic
    fun shouldSuppressConflict(first: KeyMapping, second: KeyMapping): Boolean =
        isVehicleMapping(first)
                || isVehicleMapping(second)
                || first.category == LEGACY_SBW_CATEGORY
                || second.category == LEGACY_SBW_CATEGORY

    /** Ordinary KeyMapping consumers yield only when an active vehicle binding owns this key. */
    @JvmStatic
    fun shouldSuppress(mapping: KeyMapping): Boolean {
        if (mapping is VehicleKeyMapping || !VehicleKeyConflictContext.isActive()) return false
        return mappings.any { it.claims(mapping.key) }
    }
}
