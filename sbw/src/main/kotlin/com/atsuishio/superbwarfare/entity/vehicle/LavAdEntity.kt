package com.atsuishio.superbwarfare.entity.vehicle

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import kotlin.math.max
import kotlin.math.min

class LavAdEntity(type: EntityType<LavAdEntity>, world: Level) : GeoVehicleEntity(type, world) {

    /** Client-only GAU-12 barrel-cluster angle in radians; [barrelSpinO] is the previous tick for interpolation. */
    var barrelSpin = 0f
    var barrelSpinO = 0f
    private var barrelSpinSpeed = 0f

    override fun getDamageModifier() = super.getDamageModifier()
        .custom { source, damage -> getSourceAngle(source, 0.15f) * damage }

    override fun afterVehicleTick() {
        super.afterVehicleTick()
        if (!level().isClientSide) return

        // The synced shoot timer stays above zero while the trigger is held; the cluster spins up fast and coasts down.
        val firing = (getGunData(0, 0)?.shootTimer?.get() ?: 0) > 0
        barrelSpinSpeed = if (firing) min(barrelSpinSpeed + 0.25f, 1f) else max(barrelSpinSpeed - 0.06f, 0f)
        barrelSpinO = barrelSpin
        // 0.8 rad per tick keeps the per-frame step well below the 72 degree barrel spacing, so the spin never strobes.
        barrelSpin += BARREL_SPIN_PER_TICK * barrelSpinSpeed
        if (barrelSpin > FULL_TURN) {
            barrelSpin -= FULL_TURN
            barrelSpinO -= FULL_TURN
        }
    }

    private companion object {
        const val BARREL_SPIN_PER_TICK = 0.8f
        const val FULL_TURN = 6.2831855f
    }
}
