package com.atsuishio.superbwarfare.api.projectile.impact

/**
 * Transient policy exposed only while a resolved projectile impact is being committed.
 * It lets vehicle damage skip a coarse native module hit after an add-on already resolved
 * authoritative fine-grained module volumes, without suppressing the residual hull hit.
 */
interface ProjectileImpactDamagePolicy {
    fun suppressesNativeVehicleModuleDamage(): Boolean
}
