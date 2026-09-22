package com.atsuishio.superbwarfare.api.aircraft

/** Shared selection and input policy for equipment-backed weapon channels. */
internal object AircraftStoreControls {
    const val COORDINATE_INPUT_HINT =
        "Select this missile as your primary or secondary weapon, then use that weapon's fire control."

    fun selectable(category: String?, guidedAirToAir: Boolean, remaining: Int): Boolean {
        // A depleted type retains its identity; the scheduler's held/semi state must not move
        // to a different weapon just because the final member consumed its ammunition.
        return remaining >= 0 && (AircraftCoordinateLauncher.isCoordinate(category) ||
            category == "LASER_GUIDED" || category == "AIR_TO_AIR" && guidedAirToAir)
    }

    fun acceptsLegacyShortcut(category: String?): Boolean =
        !AircraftCoordinateLauncher.isCoordinate(category)

    fun requireFireInput(category: String?, weaponSlotInput: Boolean) {
        require(weaponSlotInput || acceptsLegacyShortcut(category)) { COORDINATE_INPUT_HINT }
    }
}
