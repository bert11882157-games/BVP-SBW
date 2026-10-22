package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.api.aircraft.IncomingMissileWarning
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One frame of the radar warning receiver: threats around the aircraft by bearing, from the entities the client
 * knows about and the server's lock and missile warnings.
 *
 * Bearings are degrees off the nose in the aircraft's own frame, right positive (-180..180). [proximity] runs from
 * 0 (at the edge of the receiver's range) to 1 (on top of the aircraft); the scope plots near threats toward its
 * centre ring, as a real RWR plots the most lethal ones.
 */
data class RwrDisplayState(
    val threats: List<Threat>,
    /** The server reports a radar lock on the aircraft ([VehicleEntity.getAircraftThreatLevel] 1 or more). */
    val locked: Boolean,
    /** A missile is homing on the aircraft ([IncomingMissileWarning.INCOMING]). */
    val launch: Boolean,
    val live: Boolean,
) {
    enum class Type { MISSILE, AIR, GROUND }

    data class Threat(val bearing: Double, val proximity: Double, val type: Type, val tracking: Boolean)

    companion object {
        /** What other players see on this display: a quiet scope. */
        @JvmField
        val STILL = RwrDisplayState(emptyList(), locked = false, launch = false, live = false)

        private const val AIR_RANGE = 3000.0
        private const val GROUND_RANGE = 1500.0
        private const val MISSILE_RANGE = 2000.0
        /** cos 60 degrees: an aircraft whose nose points within this cone of us illuminates us with its radar. */
        private const val NOSE_CONE_COS = 0.5
        /** cos 25 degrees: a missile flying this close to straight at us is treated as homing on us. */
        private const val MISSILE_CONE_COS = 0.9
        private const val MAX_THREATS = 12

        @JvmStatic
        fun sample(vehicle: VehicleEntity, partialTick: Float): RwrDisplayState {
            val level = vehicle.level() as? ClientLevel ?: return STILL
            val flags = vehicle.getIncomingMissileWarning()
            val lockLevel = vehicle.getAircraftThreatLevel()
            val self = vehicle.getPosition(partialTick)
            val out = ArrayList<Threat>()
            for (e in level.entitiesForRendering()) {
                if (e === vehicle || e.isRemoved || !e.isAlive) continue
                val offset = e.getPosition(partialTick).subtract(self)
                val distance = offset.length()
                if (distance < 2.0) continue
                val threat = when {
                    e is MissileProjectile || (e is Projectile && e.javaClass.simpleName.contains("missile", true)) -> {
                        if (distance > MISSILE_RANGE) continue
                        // only missiles flying at us, closing
                        val velocity = e.deltaMovement
                        val speed = velocity.length()
                        if (speed < 0.5 || velocity.dot(offset) / (speed * distance) > -MISSILE_CONE_COS) continue
                        Type.MISSILE to distance / MISSILE_RANGE
                    }
                    e is VehicleEntity && !e.isWreck && e.isFixedWingFlightVehicle() -> {
                        if (distance > AIR_RANGE || e.getPassengers().isEmpty()) continue
                        if (!noseOn(e, offset.reverse(), distance, partialTick)) continue
                        Type.AIR to distance / AIR_RANGE
                    }
                    e is VehicleEntity && !e.isWreck && e.getPassengers().isNotEmpty() && e.onGround() -> {
                        // crewed ground vehicles with their weapon trained on us: SAM sites, AAA
                        if (distance > GROUND_RANGE || offset.y < 8.0) continue
                        if (!turretOn(e, offset.reverse(), distance, partialTick)) continue
                        Type.GROUND to distance / GROUND_RANGE
                    }
                    else -> continue
                }
                val local = vehicle.worldDirectionToVehicleLocal(offset, partialTick)
                // vehicle-local +X is left: the bearing to the right is positive
                val bearing = Math.toDegrees(atan2(-local.x, local.z))
                val proximity = (1.0 - threat.second).coerceIn(0.0, 1.0)
                out.add(Threat(bearing, proximity, threat.first, threat.first == Type.MISSILE))
            }
            // The server knows who has locked us; the client only sees who points at us. With a lock reported, the
            // nearest aircraft or ground threat is the one tracking.
            if (lockLevel > 0) {
                val tracker = out.filter { it.type != Type.MISSILE }.maxByOrNull { it.proximity }
                if (tracker != null) out[out.indexOf(tracker)] = tracker.copy(tracking = true)
            }
            out.sortByDescending { it.proximity }
            return RwrDisplayState(out.take(MAX_THREATS), locked = lockLevel > 0,
                launch = flags and IncomingMissileWarning.INCOMING != 0, live = true)
        }

        /** Whether [aircraft]'s nose points toward a target [toTarget] away (its radar cone covers us). */
        private fun noseOn(aircraft: VehicleEntity, toTarget: Vec3, distance: Double, partialTick: Float): Boolean {
            val local = aircraft.worldDirectionToVehicleLocal(toTarget, partialTick)
            return local.z / distance > NOSE_CONE_COS
        }

        /** Whether a ground vehicle's weapon (its view direction, else its hull) is trained on us. */
        private fun turretOn(ground: VehicleEntity, toTarget: Vec3, distance: Double, partialTick: Float): Boolean {
            val look = ground.getViewVector(partialTick)
            val horizontal = sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z)
            if (horizontal < 1e-3) return true
            val lookH = sqrt(look.x * look.x + look.z * look.z)
            if (lookH < 1e-3) return false
            return (look.x * toTarget.x + look.z * toTarget.z) / (lookH * horizontal) > NOSE_CONE_COS && distance > 0.0
        }
    }
}
