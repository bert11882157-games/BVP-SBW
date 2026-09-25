package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.client.overlay.weapon.FixedWingHudMetrics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth

/**
 * One frame of primary-flight-display data, taken from the same accepted instrument tuple as the HUD so the
 * display and the HUD never disagree.
 *
 * Units: attitude in degrees (nose up and right wing down positive), heading 0..360 clockwise from north, speed in
 * km/h (the HUD's unit), altitude in blocks (metres) above the world's zero, vertical speed in m/s.
 */
data class FlightDisplayState(
    val pitchUp: Double,
    val rollRight: Double,
    val heading: Double,
    val speedKmh: Double,
    val altitude: Double,
    val verticalSpeed: Double,
    val mach: Double?,
    val throttle: Double?,
    val liftG: Double?,
) {
    companion object {
        /** What other players see on this display: a powered, level, stationary picture. */
        @JvmField
        val STILL = FlightDisplayState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null)

        private var smoothedVs = 0.0
        private var smoothedFor = -1

        /** Live state of [vehicle] at [partialTick], or null when its attitude is not yet known. */
        @JvmStatic
        fun sample(vehicle: VehicleEntity, partialTick: Float): FlightDisplayState? {
            val fixedWing = vehicle.isFixedWingFlightVehicle()
            val strategy = if (fixedWing) vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy else null
            val snapshot = if (strategy != null && partialTick.isFinite()) {
                FixedWingHudMetrics.acceptedSnapshot(vehicle.getVehicleFlightPresentationSnapshot(partialTick))
            } else null
            val yaw = snapshot?.bodyYaw ?: FixedWingHudMetrics.angle(vehicle.yRotO, vehicle.yRot, partialTick) ?: return null
            val pitch = snapshot?.bodyPitch ?: FixedWingHudMetrics.angle(vehicle.xRotO, vehicle.xRot, partialTick) ?: return null
            val roll = snapshot?.bodyRoll ?: FixedWingHudMetrics.angle(vehicle.prevRoll, vehicle.roll, partialTick) ?: return null
            if (!yaw.isFinite() || !pitch.isFinite() || !roll.isFinite()) return null

            val speed = (if (fixedWing) FixedWingHudMetrics.speedKmh(snapshot) else null) ?: (vehicle.absoluteSpeed * 72.0)
            val y = Mth.lerp(partialTick.toDouble(), vehicle.yo, vehicle.y)
            val rawVs = vehicle.deltaMovement.y * 20.0
            if (smoothedFor != vehicle.id) {
                smoothedFor = vehicle.id
                smoothedVs = rawVs
            }
            smoothedVs += (rawVs - smoothedVs) * 0.08
            val mach = if (strategy != null) {
                FixedWingHudMetrics.mach(snapshot, y, vehicle.level().seaLevel.toDouble(), strategy.handling.simulationLengthScale)
            } else speed / 3.6 / 340.3
            val throttle = if (fixedWing) snapshot?.throttle else vehicle.power.toDouble()
            return FlightDisplayState(
                pitchUp = -pitch.toDouble(),
                rollRight = roll.toDouble(),
                heading = Mth.positiveModulo(yaw.toDouble() + 180.0, 360.0),
                speedKmh = speed.takeIf { it.isFinite() } ?: 0.0,
                altitude = y,
                verticalSpeed = smoothedVs.takeIf { it.isFinite() } ?: 0.0,
                mach = mach?.takeIf { it.isFinite() },
                throttle = throttle?.takeIf { it.isFinite() },
                liftG = FixedWingHudMetrics.signedLiftG(snapshot),
            )
        }
    }
}
