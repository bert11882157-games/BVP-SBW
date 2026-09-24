package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/** Same accepted motion source as the wing rig, including distant vehicle copies. */
object AircraftMountPresentation {
    @JvmStatic fun speed(vehicle: VehicleEntity, partialTick: Float): Double {
        if (vehicle.isWreck) return 0.0
        val far = FarVehicleCopies.frame(vehicle)
        val speed = if (far != null) {
            if (far.fixedWingControls == null || far.snapshot.wreck) return 0.0
            val snapshot = far.snapshot
            Math.hypot(Math.hypot(snapshot.motionX,snapshot.motionY),snapshot.motionZ)
        } else {
            val flight = vehicle.getVehicleFlightPresentationSnapshot(partialTick)
            if (flight.serverTick <= 0 || !flight.throttle.isFinite()) return 0.0
            flight.motion.length()
        }
        return speed.takeIf { it.isFinite() } ?: 0.0
    }
}
