package com.atsuishio.superbwarfare.data.vehicle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Authored HUD system classes; LMG includes rifle-calibre coaxial and aircraft machine guns. */
@Serializable
enum class WeaponSystemKind { AUTOCANNON, TANK_CANNON, HMG, LMG, ATGM, UNKNOWN }

/**
 * Synced presentation metadata under sbw/weapon_system_metadata.
 * A vehicle-type resource owns exact seat channel [systems]; a Combat.WeaponId resource owns
 * one [kind]. The record never creates a weapon, selects ammunition, or changes firing state.
 */
@Serializable
data class WeaponSystemMetadata(
    @SerialName("Kind") val kind: WeaponSystemKind? = null,
    @SerialName("Systems") val systems: Map<String, WeaponSystemKind> = emptyMap(),
) {
    init {
        require(kind == null || systems.isEmpty()) { "use either Kind or Systems, not both" }
        require(systems.size <= 64) { "at most 64 weapon channels per vehicle metadata record" }
        require(systems.keys.all { it.matches(CHANNEL_ID) }) { "invalid weapon channel identity" }
    }

    companion object {
        private val CHANNEL_ID = Regex("[A-Za-z][A-Za-z0-9_.-]{0,95}")

        /** Exact channel declaration wins, including explicit UNKNOWN. Missing metadata stays null. */
        fun resolve(
            vehicleId: String?,
            channelId: String,
            projectileWeaponId: String?,
            lookup: (String) -> WeaponSystemMetadata?,
        ): WeaponSystemKind? = vehicleId?.let(lookup)?.systems?.get(channelId)
            ?: projectileWeaponId?.let(lookup)?.kind
    }
}
