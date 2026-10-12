package com.atsuishio.superbwarfare.data.vehicle.subdata

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed runtime binding for an independently pitched AGS-30 child of the vehicle turret.
 *
 * The pivot is authored in the native Turret frame.  The attachment graph supplies the
 * authored grenade-launcher muzzle as a child of [NATIVE_FRAME]; the resolver applies the
 * selected turret's actual pitch to this frame for both gameplay and immutable presentation.
 */
@Serializable
enum class VehicleAgs30PitchParent {
    @SerialName("Turret")
    TURRET,
}

@Serializable
class VehicleAgs30PitchFrame {
    @SerialName("Schema")
    var schema: String = SCHEMA

    @SerialName("Parent")
    var parent: VehicleAgs30PitchParent? = VehicleAgs30PitchParent.TURRET

    /** AGS-30 pitch pivot in the authored native Turret frame. */
    @SerialName("Pivot")
    var pivot: SerializedVec3? = null

    /** Exact data-authored muzzle attachment consumed by all shot/HUD/effect paths. */
    @SerialName("MuzzleAttachment")
    var muzzleAttachment: String? = null

    /** Typed selected-weapon identity; never inferred from a translated display label. */
    @SerialName("WeaponId")
    var weaponId: String = WEAPON_ID

    fun hasTypedIdentity(): Boolean {
        val authoredPivot = pivot ?: return false
        return schema == SCHEMA && parent == VehicleAgs30PitchParent.TURRET &&
            weaponId == WEAPON_ID && muzzleAttachment?.isNotBlank() == true &&
            authoredPivot.x.isFinite() && authoredPivot.y.isFinite() && authoredPivot.z.isFinite()
    }

    companion object {
        const val SCHEMA = "berts_vehicle_pack:ags30_pitch/v1"
        const val NATIVE_FRAME = "Ags30Pitch"
        const val WEAPON_ID = "GrenadeLauncher"
    }
}
