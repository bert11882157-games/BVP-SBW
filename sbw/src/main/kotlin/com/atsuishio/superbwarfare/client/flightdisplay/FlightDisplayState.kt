package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.client.overlay.weapon.FixedWingHudMetrics
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One frame of primary-flight-display data, taken from the same accepted instrument tuple as the HUD so the
 * display and the HUD never disagree.
 *
 * Units: attitude in degrees (nose up and right wing down positive), heading 0..360 clockwise from north, speed in
 * km/h (the HUD's unit), altitude in blocks (metres) above the world's zero, vertical speed in m/s. The flight path
 * ([pathUp], [pathRight]) is the direction of motion in degrees off the nose in the aircraft's own frame; angle of
 * attack is the flight path's angle below the nose in the aircraft's plane of symmetry.
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
    val angleOfAttack: Double? = null,
    val pathUp: Double? = null,
    val pathRight: Double? = null,
    /** Rotorcraft only: rotor spool (engine N2) and collective torque, fractions 0..1. */
    val rotorRpm: Double? = null,
    val torque: Double? = null,
    /** Fuel (the vehicle's energy store) remaining, 0..1; null without one. */
    val fuel: Double? = null,
    /** Height above the terrain under the aircraft, metres; null when unknown. */
    val radarAltitude: Double? = null,
    /** Yaw rate, degrees per second, nose right positive. */
    val yawRate: Double = 0.0,
    val engineRunning: Boolean = true,
    val afterburner: Boolean = false,
    /** Airbrake extension 0..1. */
    val airbrake: Double = 0.0,
    /** Landing gear extension 0..1 (1 down); null for an aircraft without retractable gear. */
    val gear: Double? = null,
    /** Hull health remaining, 0..1. */
    val health: Double = 1.0,
    /** Time of day in ticks (0..24000, 0 = 06:00); null when unknown. */
    val dayTime: Long? = null,
    /** Rounds left in the local crew member's selected weapon; null when there is none, Int.MAX_VALUE when unlimited. */
    val ammo: Int? = null,
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
            // flight path in the aircraft's frame (vehicle-local +X left, +Y up, +Z forward), once it moves
            val motion = vehicle.worldDirectionToVehicleLocal(snapshot?.motion ?: vehicle.deltaMovement, partialTick)
            val moving = motion.z > 0.05 && motion.x.isFinite() && motion.y.isFinite()
            val aoa = if (moving) Math.toDegrees(atan2(-motion.y, motion.z)) else null
            val pathUp = if (moving) Math.toDegrees(atan2(motion.y, sqrt(motion.x * motion.x + motion.z * motion.z))) else null
            val pathRight = if (moving) Math.toDegrees(atan2(-motion.x, motion.z)) else null
            // rotorcraft: the rotor spool and the collective from the presentation snapshot, once one arrived
            val rotor = if (!fixedWing) vehicle.getVehicleFlightPresentationSnapshot(partialTick).takeIf { it.sequence != 0 } else null
            val surfaces = snapshot?.controlSurfaces
            val level = vehicle.level()
            val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(vehicle.x), Mth.floor(vehicle.z))
            val energy = vehicle.getEnergyStorage()
            val fuel = energy?.takeIf { it.maxEnergyStored > 0 }?.let { it.energyStored.toDouble() / it.maxEnergyStored }
            val maxHealth = vehicle.getMaxHealth()
            val rider = Minecraft.getInstance().player?.takeIf { it.vehicle === vehicle }
            val ammo = rider?.let { vehicle.getAmmoCount(it) }?.takeIf { it >= 0 }
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
                angleOfAttack = aoa?.takeIf { it.isFinite() },
                pathUp = pathUp?.takeIf { it.isFinite() },
                pathRight = pathRight?.takeIf { it.isFinite() },
                rotorRpm = rotor?.rotorLift?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
                torque = rotor?.collective?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
                fuel = fuel?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
                radarAltitude = (y - ground).takeIf { it.isFinite() }?.coerceAtLeast(0.0),
                yawRate = (Mth.wrapDegrees(vehicle.yRot - vehicle.yRotO) * 20.0).takeIf { it.isFinite() } ?: 0.0,
                engineRunning = vehicle.engineRunning(),
                afterburner = surfaces?.afterburnerActive ?: false,
                airbrake = surfaces?.airbrake?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                gear = if (vehicle.hasFixedWingLandingGear()) vehicle.synchedGearRot.toDouble().coerceIn(0.0, 1.0) else null,
                health = if (maxHealth > 0f) (vehicle.health / maxHealth).toDouble().coerceIn(0.0, 1.0) else 1.0,
                dayTime = level.dayTime % 24000L,
                ammo = ammo,
            )
        }
    }
}
