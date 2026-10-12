package com.atsuishio.superbwarfare.data.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Client-safe static launch identity for advisory prediction; no damage or firing state lives here. */
@Serializable
data class NominalBallisticsDescriptor(
    @SerialName("Supported")
    var supported: Boolean = true,
    @SerialName("ProjectileType")
    var projectileType: String = "",
    @SerialName("ProjectileProfile")
    var projectileProfile: String? = null,
    @SerialName("ProjectileLife")
    var projectileLife: Int = -1,
)
