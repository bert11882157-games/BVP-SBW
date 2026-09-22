package com.atsuishio.superbwarfare.api.aircraft

/** Shared selection and input policy for equipment-backed weapon channels. */
internal object AircraftStoreControls {
    const val COORDINATE_INPUT_HINT =
        "Select this missile as your primary or secondary weapon, then use that weapon's fire control."

    fun selectable(category: String?, guidedAirToAir: Boolean, remaining: Int): Boolean {
        // Keep an empty coordinate launcher in its equipped slot. Removing it here would let a
        // held trigger move to another pylon, whose separately assigned target may be different.
        if (AircraftCoordinateLauncher.isCoordinate(category)) return remaining >= 0
        return (category == "LASER_GUIDED" || category == "AIR_TO_AIR" && guidedAirToAir) &&
            remaining > 0
    }

    fun acceptsLegacyShortcut(category: String?): Boolean =
        !AircraftCoordinateLauncher.isCoordinate(category)

    fun requireFireInput(category: String?, weaponSlotInput: Boolean) {
        require(weaponSlotInput || acceptsLegacyShortcut(category)) { COORDINATE_INPUT_HINT }
    }
}
