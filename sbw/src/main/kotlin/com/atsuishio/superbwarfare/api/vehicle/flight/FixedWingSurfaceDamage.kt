package com.atsuishio.superbwarfare.api.vehicle.flight

/** Aerodynamic effects of independently disabled surfaces. Both wings share one authority penalty. */
data class FixedWingSurfaceDamage(
    val leftWing: Boolean = false,
    val rightWing: Boolean = false,
    val leftElevator: Boolean = false,
    val rightElevator: Boolean = false,
    val rudder: Boolean = false,
) {
    val rollAuthority: Double get() = if (leftWing || rightWing) 0.5 else 1.0
    val pitchAuthority: Double get() = when {
        leftElevator && rightElevator -> 0.1
        leftElevator || rightElevator -> 0.55
        else -> 1.0
    }
    val yawAuthority: Double get() = if (rudder) 0.0 else 1.0
    /** Positive body roll is right bank. Opposite damaged-wing biases cancel. */
    val rollBiasDegreesPerSecond: Double get() =
        ((if (rightWing) 1 else 0) - (if (leftWing) 1 else 0)) * 1.5

    companion object {
        val INTACT = FixedWingSurfaceDamage()
        fun from(vehicle: com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity): FixedWingSurfaceDamage {
            val modules = com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules
            return FixedWingSurfaceDamage(
                modules.damaged(vehicle, modules.WING_LEFT), modules.damaged(vehicle, modules.WING_RIGHT),
                modules.damaged(vehicle, modules.ELEVATOR_LEFT), modules.damaged(vehicle, modules.ELEVATOR_RIGHT),
                modules.damaged(vehicle, modules.RUDDER),
            )
        }
    }
}
