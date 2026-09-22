package com.atsuishio.superbwarfare.api.vehicle.weapon

import net.minecraft.resources.ResourceLocation
import kotlinx.serialization.Serializable

/**
 * Explicit, per-seat weapon pairing.  References are exact authored weapon ids; no display
 * text, vehicle name, or substring inference is allowed at this boundary.
 */
data class VehicleWeaponPair(
    val primaryWeaponId: String,
    val secondaryWeaponId: String?,
) {
    init {
        require(primaryWeaponId.isNotBlank()) { "primaryWeaponId must not be blank" }
        require(secondaryWeaponId == null || secondaryWeaponId.isNotBlank()) {
            "secondaryWeaponId must be null or non-blank"
        }
        require(secondaryWeaponId == null || primaryWeaponId != secondaryWeaponId) {
            "primary and secondary weapon ids must differ"
        }
    }

    /**
     * Resolves the authored metadata for an exact member selection.  This is deliberately a
     * membership-only compatibility helper: selecting/cycling a slot never silently promotes an
     * authored secondary or mutates the other slot.  Live slot order comes from the seat's
     * filtered weapon list in [VehicleEntity].
     */
    fun forSelected(selectedWeaponId: String?): ActiveVehicleWeaponPair? {
        if (selectedWeaponId == null ||
            (selectedWeaponId != primaryWeaponId && selectedWeaponId != secondaryWeaponId)
        ) return null
        return ActiveVehicleWeaponPair(primaryWeaponId, secondaryWeaponId)
    }
}

/** The currently resolved exact primary and secondary slot identities. */
data class ActiveVehicleWeaponPair(
    val primaryWeaponId: String,
    val secondaryWeaponId: String?,
)

/** Independently cycleable slot; both slots are always resolved from one ordered seat list. */
@Serializable
enum class VehicleWeaponSlot {
    PRIMARY,
    SECONDARY,
}

/** Implemented by vehicles with an explicitly authored primary/secondary weapon relationship. */
interface VehicleWeaponPairProvider {
    fun getVehicleWeaponPair(seatIndex: Int): VehicleWeaponPair?
}

/** Shared action id for Spacebar secondary fire; transport remains VehicleActionInputMessage. */
object VehicleWeaponActionIds {
    @JvmField
    val FIRE_SECONDARY = ResourceLocation("superbwarfare", "fire_secondary_weapon")
}
