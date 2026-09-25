package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** The retained fuselage section remains server-owned; other sections are cosmetic. */
object AircraftFuselageWreck {
    const val LIFETIME_TICKS = WreckDebrisPhysics.WRECK_LIFETIME_TICKS
    internal fun bounce(incoming: Vec3, completed: Int): Vec3 =
        if (completed == 0) WreckDebrisPhysics.deflect(incoming, Vec3(0.0, 1.0, 0.0))
        else Vec3(incoming.x, 0.0, incoming.z)

    @JvmStatic fun fragmented(vehicle: VehicleEntity): Boolean =
        vehicle.computed().aircraftTerrainContact?.wreckSections?.size == 4

    fun contact(vehicle: VehicleEntity, incoming: Vec3, below: Boolean) {
        if (vehicle.level().isClientSide || !vehicle.isWreck || !fragmented(vehicle)) return
        val now = vehicle.level().gameTime
        val first = vehicle.aircraftWreckImpactTime < 0L
        if (first) {
            vehicle.aircraftWreckImpactTime = now
            // The lethal strike may already have stored the larger pre-impact velocity.
            AircraftWreckBreakup.recordMomentum(vehicle, incoming)
            AircraftWreckBreakup.detach(vehicle, 3, incoming)
        }
        if (!first && (!below || incoming.y >= -.02 ||
            vehicle.aircraftLastWreckBounce != Long.MIN_VALUE && now - vehicle.aircraftLastWreckBounce < 2)) return
        // Keep the collision solver's wall response; only a supporting face may bounce up.
        if (!below) return
        vehicle.deltaMovement = bounce(incoming, vehicle.aircraftWreckBounces)
        vehicle.aircraftWreckBounces = (vehicle.aircraftWreckBounces + 1).coerceAtMost(3)
        vehicle.aircraftLastWreckBounce = now
    }

    /** Hull momentum: Coulomb sliding friction on the ground, light drag in air, heavy drag in fluid. */
    internal fun hullMotion(previous: Vec3, resting: Boolean, inFluid: Boolean, gravity: Double): Vec3 = when {
        inFluid -> previous.scale(.92).add(0.0, -gravity, 0.0)
        resting -> WreckDebrisPhysics.slide(previous.scale(WreckDebrisPhysics.AIR_DRAG),
            WreckDebrisPhysics.FUSELAGE_FRICTION, gravity).let {
                if (it.horizontalDistanceSqr() < .000025) Vec3(0.0, -gravity, 0.0) else it.add(0.0, -gravity, 0.0)
            }
        else -> previous.scale(WreckDebrisPhysics.AIR_DRAG).add(0.0, -gravity, 0.0)
    }

    internal fun step(vehicle: VehicleEntity, input: VehicleFlightInputContext, gravity: Double): VehicleFlightTickResult? {
        if (vehicle.aircraftWreckImpactTime < 0L) return null
        val resting = vehicle.onGround() && !input.inFluid
        val motion = hullMotion(input.previousMotion, resting, input.inFluid, gravity)
        val sign = if (vehicle.uuid.leastSignificantBits and 1L == 0L) 1 else -1
        fun settled(angle: Double, offset: Double = 0.0): Float {
            val target = offset + kotlin.math.round((angle - offset) / 180.0) * 180.0
            return Mth.wrapDegrees(angle + Mth.wrapDegrees(target - angle) * .16).toFloat()
        }
        val section = vehicle.computed().aircraftTerrainContact?.wreckSections?.getOrNull(1)
        val size = section?.maximum?.subtract(section.minimum)
        val restingRoll = if (size != null && size.x < size.y * .5) 90.0 else 0.0
        return VehicleFlightTickResult(motion, 0.0, 0.0, 0.0, 0.0,
            Mth.wrapDegrees(input.bodyYawDegrees + if (resting) 0.0 else sign * .6).toFloat(),
            if (resting) settled(input.bodyPitchDegrees) else Mth.wrapDegrees(input.bodyPitchDegrees + .7).toFloat(),
            if (resting) settled(input.bodyRollDegrees, restingRoll) else Mth.wrapDegrees(input.bodyRollDegrees + sign * 1.0).toFloat(), true)
    }
}
