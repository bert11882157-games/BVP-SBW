package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity

/**
 * When the pilot may use the loadout editor. The server admits OPEN and every fitting request with
 * these rules; the client applies the same ones to close the editor and grey out its controls.
 */
object AircraftLoadoutAccess {
    /** Squared speed, in (blocks per tick)², at or below which an aircraft counts as stopped (1 m/s). */
    const val MAX_STATIONARY_SPEED_SQR = 0.0025
    const val IN_MOTION_MESSAGE = "Pylon menu inaccessible while vehicle is in motion"

    /** A non-finite speed counts as moving. */
    @JvmStatic fun inMotion(speedSqrBlocksPerTick: Double): Boolean = !(speedSqrBlocksPerTick <= MAX_STATIONARY_SPEED_SQR)

    @JvmStatic fun inMotion(vehicle: VehicleEntity): Boolean = inMotion(vehicle.deltaMovement.lengthSqr())

    /** Equipment changes additionally need the aircraft on the ground; a still hover may only look. */
    @JvmStatic fun canRefit(vehicle: VehicleEntity): Boolean = vehicle.onGround() && !inMotion(vehicle)
}
