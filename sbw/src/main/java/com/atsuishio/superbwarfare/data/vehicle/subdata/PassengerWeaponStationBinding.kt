package com.atsuishio.superbwarfare.data.vehicle.subdata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stable runtime binding for a passenger-operated weapon station.
 *
 * The field is nullable in [com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData] so
 * legacy PassengerMachineGun stations retain their native turret-relative behavior.  A HULL
 * binding is usable only when its exact weapon identity and authored camera/muzzle attachments
 * are present; there is deliberately no coordinate fallback here.
 */
@Serializable
enum class PassengerWeaponStationParent {
    @SerialName("TURRET")
    TURRET,

    @SerialName("HULL")
    HULL,
}

/** Typed station kind; the weapon id remains the authoritative selected-weapon identity. */
@Serializable
enum class PassengerWeaponStationWeaponKind {
    @SerialName("HEAVY_MACHINE_GUN")
    HEAVY_MACHINE_GUN,

    @SerialName("RECOILLESS_GUN")
    RECOILLESS_GUN,

    @SerialName("LOW_PRESSURE_CANNON")
    LOW_PRESSURE_CANNON,

    @SerialName("ROCKET_POD")
    ROCKET_POD,

    /** Malformed/unfinished authoring is fail-closed by VehicleEntity. */
    @SerialName("UNSPECIFIED")
    UNSPECIFIED,
}

@Serializable
class PassengerWeaponStationBinding {
    @SerialName("Schema")
    var schema: String = SCHEMA

    @SerialName("Parent")
    var parent: PassengerWeaponStationParent? = PassengerWeaponStationParent.TURRET

    @SerialName("WeaponId")
    var weaponId: String = ""

    @SerialName("WeaponKind")
    var weaponKind: PassengerWeaponStationWeaponKind? = PassengerWeaponStationWeaponKind.UNSPECIFIED

    /** Explicit authored marker names; HULL stations cannot use an invented/default marker. */
    @SerialName("CameraAttachment")
    var cameraAttachment: String? = null

    @SerialName("MuzzleAttachment")
    var muzzleAttachment: String? = null

    fun hasTypedIdentity(): Boolean =
        schema == SCHEMA && parent != null && weaponId.isNotBlank() &&
            weaponKind != null && weaponKind != PassengerWeaponStationWeaponKind.UNSPECIFIED

    companion object {
        const val SCHEMA = "berts_vehicle_pack:passenger_bed_weapon_station/v1"
    }
}
