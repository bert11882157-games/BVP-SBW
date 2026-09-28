package com.atsuishio.superbwarfare.api.vehicle.flight

/**
 * Aerodynamic effect of wing damage (owner 2026-09-28). A wing at or below 25 % of its pool has a dead aileron:
 * each dead aileron halves roll authority. A wing that is gone also rolls the aircraft towards the missing side.
 * Elevators and the rudder are part of the hull and never lose authority.
 */
data class FixedWingSurfaceDamage(
    val leftAileronDead: Boolean = false,
    val rightAileronDead: Boolean = false,
    val leftWingGone: Boolean = false,
    val rightWingGone: Boolean = false,
) {
    val rollAuthority: Double get() =
        (if (leftAileronDead || leftWingGone) 0.5 else 1.0) * (if (rightAileronDead || rightWingGone) 0.5 else 1.0)
    val pitchAuthority: Double get() = 1.0
    val yawAuthority: Double get() = 1.0
    /** Positive body roll is right bank: the side without its wing drops. */
    val rollBiasDegreesPerSecond: Double get() =
        ((if (rightWingGone) 1 else 0) - (if (leftWingGone) 1 else 0)) * WING_LOSS_ROLL_DEGREES_PER_SECOND +
            ((if (rightAileronDead) 1 else 0) - (if (leftAileronDead) 1 else 0)) * 1.5

    companion object {
        const val AILERON_DEAD_FRACTION = 0.25
        const val WING_LOSS_ROLL_DEGREES_PER_SECOND = 55.0
        val INTACT = FixedWingSurfaceDamage()
        fun from(vehicle: com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity): FixedWingSurfaceDamage {
            val modules = com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
            val gone = AircraftWreckBreakup.mask(vehicle)
            return FixedWingSurfaceDamage(
                modules.aileronDead(vehicle, modules.WING_LEFT), modules.aileronDead(vehicle, modules.WING_RIGHT),
                gone and AircraftWreckBreakup.LEFT != 0, gone and AircraftWreckBreakup.RIGHT != 0,
            )
        }
    }
}
