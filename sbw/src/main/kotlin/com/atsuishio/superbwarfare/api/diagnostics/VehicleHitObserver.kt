package com.atsuishio.superbwarfare.api.diagnostics

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.damagesource.DamageSource

/**
 * Diagnostics hook: sees every damage request a vehicle receives and how much health it actually lost (0 for a hit
 * the armour stopped). Null in normal games, so the only cost is one field read per hit.
 */
object VehicleHitObserver {
    fun interface Listener { fun hit(vehicle: VehicleEntity, source: DamageSource, requested: Float, lost: Float) }

    @JvmStatic @Volatile var listener: Listener? = null
}
