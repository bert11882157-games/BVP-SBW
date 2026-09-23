package com.atsuishio.superbwarfare.api.vehicle.flight

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/** The retained fuselage section remains server-owned; other sections are cosmetic. */
object AircraftFuselageWreck {
    const val LIFETIME_TICKS = 200
    internal fun bounce(incoming: Vec3, completed: Int): Vec3 = if (completed >= 2)
        Vec3(incoming.x * .94, 0.0, incoming.z * .94) else
        Vec3(incoming.x * .82, (kotlin.math.abs(incoming.y) * .16).coerceIn(.08, .26), incoming.z * .82)

    fun contact(vehicle: VehicleEntity, incoming: Vec3, below: Boolean) {
        if (vehicle.level().isClientSide || !vehicle.isWreck || vehicle.computed().aircraftTerrainContact?.wreckSections?.size != 4) return
        val now = vehicle.level().gameTime
        val first = vehicle.aircraftWreckImpactTime < 0L
        if (first) {
            vehicle.aircraftWreckImpactTime = now
            vehicle.aircraftWreckMotionX = incoming.x.toFloat()
            vehicle.aircraftWreckMotionY = incoming.y.toFloat()
            vehicle.aircraftWreckMotionZ = incoming.z.toFloat()
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

    internal fun step(vehicle: VehicleEntity, input: VehicleFlightInputContext, gravity: Double): VehicleFlightTickResult? {
        if (vehicle.aircraftWreckImpactTime < 0L) return null
        val resting = vehicle.onGround() || input.inFluid
        val drag = if (resting) .94 else .996
        val horizontal = input.previousMotion.scale(drag)
        val motion = if (resting && horizontal.horizontalDistanceSqr() < .000025)
            Vec3(0.0, -gravity, 0.0) else horizontal.add(0.0, -gravity, 0.0)
        val sign = if (vehicle.uuid.leastSignificantBits and 1L == 0L) 1 else -1
        return VehicleFlightTickResult(motion, 0.0, 0.0, 0.0, 0.0,
            Mth.wrapDegrees(input.bodyYawDegrees + if (resting) 0.0 else sign * .6).toFloat(),
            Mth.wrapDegrees(input.bodyPitchDegrees + if (resting) 0.0 else .7).toFloat(),
            Mth.wrapDegrees(input.bodyRollDegrees + if (resting) 0.0 else sign * 1.0).toFloat(), true)
    }
}
