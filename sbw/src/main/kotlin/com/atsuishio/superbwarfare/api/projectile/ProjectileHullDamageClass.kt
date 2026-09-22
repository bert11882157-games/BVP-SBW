package com.atsuishio.superbwarfare.api.projectile

import kotlinx.serialization.Serializable

/** Typed direct-hull damage class; authoring may emit this when broad damageType is ambiguous. */
@Serializable
enum class ProjectileHullDamageClass {
    DEFAULT,
    ATGM,
    HEAT,
    HEAT_FS,
    HE,
    APHE,
    APCR,
    APDS,
    APFSDS,
}
