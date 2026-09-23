package com.atsuishio.superbwarfare.api.aircraft

/**
 * Explicit projectile-family policy for direct aircraft HP, separate from physical caliber.
 * Called only after a genuine server aircraft hit is claimed. Implementations must read immutable
 * fired-projectile metadata, not current shooter equipment, and must not mutate gameplay state.
 * The finite positive return value overrides caliber-based HP; invalid values fail closed.
 * This does not change ground damage, collision, or projectile effects and disposal.
 */
fun interface AircraftProjectileDamageOverride {
    fun aircraftDirectHitDamage(targetMaxHealth: Float): Float
}
