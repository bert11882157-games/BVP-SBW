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

    @SerialName("AUTOCANNON")
    AUTOCANNON,

    /** Malformed/unfinished authoring is fail-closed by VehicleEntity. */
    @SerialName("UNSPECIFIED")
    UNSPECIFIED,
}

@Serializable
class PassengerWeaponFireExclusion {
    @SerialName("Yaw") var yaw: List<Float> = emptyList()
    @SerialName("Pitch") var pitch: List<Float> = emptyList()

    fun valid(): Boolean = rangeValid(yaw, 180F) && rangeValid(pitch, 90F)
    fun contains(yawDegrees: Float, pitchDegrees: Float): Boolean =
        yawDegrees in yaw[0]..yaw[1] && pitchDegrees in pitch[0]..pitch[1]

    private fun rangeValid(range: List<Float>, limit: Float): Boolean = range.size == 2 &&
        range.all { it.isFinite() && it in -limit..limit } && range[0] <= range[1]
}

@Serializable
class PassengerWeaponStationBinding {
    @SerialName("Schema")
    var schema: String = SCHEMA

    @SerialName("Parent")
    var parent: PassengerWeaponStationParent? = PassengerWeaponStationParent.TURRET

    @SerialName("WeaponId")
    var weaponId: String = ""

    /** Empty retains the legacy single-weapon binding; a bank shares this one articulation. */
    @SerialName("WeaponIds")
    var weaponIds: List<String> = emptyList()

    @SerialName("BaseYawDegrees")
    var baseYawDegrees: Float = 0F

    /** Angles share the authored slew-range convention; exclusions block fire, not aiming. */
    @SerialName("FireExclusions")
    var fireExclusions: List<PassengerWeaponFireExclusion> = emptyList()

    @SerialName("WeaponKind")
    var weaponKind: PassengerWeaponStationWeaponKind? = PassengerWeaponStationWeaponKind.UNSPECIFIED

    /** Explicit authored marker names; HULL stations cannot use an invented/default marker. */
    @SerialName("CameraAttachment")
    var cameraAttachment: String? = null

    @SerialName("MuzzleAttachment")
    var muzzleAttachment: String? = null

    fun hasTypedIdentity(): Boolean =
        schema == SCHEMA && parent != null && weaponId.isNotBlank() &&
            baseYawDegrees.isFinite() && baseYawDegrees in -180F..180F &&
            hasValidWeaponIds() && fireExclusions.size <= 8 && fireExclusions.all { it.valid() } &&
            weaponKind != null && weaponKind != PassengerWeaponStationWeaponKind.UNSPECIFIED

    fun containsWeapon(name: String): Boolean = hasTypedIdentity() &&
        if (weaponIds.isEmpty()) name == weaponId else name in weaponIds

    fun permitsFire(yawDegrees: Float, pitchDegrees: Float): Boolean =
        hasTypedIdentity() && (fireExclusions.isEmpty() ||
            (yawDegrees.isFinite() && pitchDegrees.isFinite() &&
                fireExclusions.none { it.contains(yawDegrees, pitchDegrees) }))

    private fun hasValidWeaponIds(): Boolean {
        if (weaponIds.isEmpty()) return true
        if (weaponIds.size !in 1..8 || weaponIds.first() != weaponId) return false
        for (index in weaponIds.indices) {
            val name = weaponIds[index]
            if (!name.matches(WEAPON_ID)) return false
            for (previous in 0 until index) if (weaponIds[previous] == name) return false
        }
        return true
    }

    companion object {
        const val SCHEMA = "berts_vehicle_pack:passenger_bed_weapon_station/v1"
        private val WEAPON_ID = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")
    }
}
