package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One frame of the radar page: contacts the aircraft's radar sees, from the entities the client knows about.
 *
 * Bearings are degrees off the nose (right positive), ranges in blocks, relative altitude in blocks (above
 * positive). The radar covers [AZIMUTH_LIMIT] degrees either side of the nose out to the aircraft's radar range.
 */
data class RadarDisplayState(
    val rangeScale: Double,
    val heading: Double,
    val sweep: Double,
    val contacts: List<Contact>,
    val live: Boolean,
) {
    data class Contact(val bearing: Double, val range: Double, val relativeAltitude: Double, val air: Boolean,
                       val locked: Boolean, val closing: Double)

    companion object {
        const val AZIMUTH_LIMIT = 60.0
        private const val DEFAULT_RANGE = 1000.0
        private val SCALES = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0)

        /** What other players see on this display: an empty scope at a fixed scale. */
        @JvmField
        val STILL = RadarDisplayState(1000.0, 0.0, 0.0, emptyList(), false)

        @JvmStatic
        fun sample(vehicle: VehicleEntity, partialTick: Float): RadarDisplayState {
            val level = vehicle.level()
            val radar = runCatching { AircraftArmamentManager.definition(vehicle)?.getAsJsonObject("Radar") }.getOrNull()
            val range = radar?.get("Range")?.asDouble?.takeIf { it > 0 } ?: DEFAULT_RANGE
            val maxScale = SCALES.firstOrNull { it >= range } ?: SCALES.last()
            val yaw = Mth.lerp(partialTick, vehicle.yRotO, vehicle.yRot).toDouble()
            val heading = Mth.positiveModulo(yaw + 180.0, 360.0)
            val ox = Mth.lerp(partialTick.toDouble(), vehicle.xo, vehicle.x)
            val oy = Mth.lerp(partialTick.toDouble(), vehicle.yo, vehicle.y)
            val oz = Mth.lerp(partialTick.toDouble(), vehicle.zo, vehicle.z)
            val locked = if (ClientEventHandler.lockOnVehicle) ClientEventHandler.lockingEntityVehicle else null
            val out = ArrayList<Contact>()
            val self = Minecraft.getInstance().player
            for (e in (level as? net.minecraft.client.multiplayer.ClientLevel)?.entitiesForRendering() ?: emptyList()) {
                if (e === vehicle || e.isRemoved) continue
                val target = when {
                    e is VehicleEntity && !e.isWreck -> e
                    e is Player && e !== self && e.vehicle == null && !e.isSpectator && !e.onGround() -> e
                    else -> continue
                }
                val dx = target.x - ox; val dz = target.z - oz
                val horizontal = sqrt(dx * dx + dz * dz)
                if (horizontal > range || horizontal < 4.0) continue
                // Minecraft yaw 0 faces +Z (south); bearing of the target relative to the nose, right positive.
                val targetYaw = Math.toDegrees(atan2(-dx, dz))
                val bearing = Mth.wrapDegrees(targetYaw - yaw)
                if (kotlin.math.abs(bearing) > AZIMUTH_LIMIT) continue
                val dy = target.y - oy
                val air = !target.onGround() && (target !is VehicleEntity || target.isFixedWingFlightVehicle()
                        || target.y - level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    target.blockX, target.blockZ) > 4)
                val rel = target.deltaMovement.subtract(vehicle.deltaMovement)
                val closing = -(rel.x * dx + rel.z * dz) / horizontal * 20.0 * 3.6
                out.add(Contact(bearing, horizontal, dy, air, target === locked, closing))
            }
            val time = level.gameTime + partialTick.toDouble()
            // Scan bar: +-60 degrees and back every 2 seconds.
            val phase = (time % 80.0) / 40.0
            val sweep = if (phase < 1.0) -AZIMUTH_LIMIT + phase * 2 * AZIMUTH_LIMIT else AZIMUTH_LIMIT - (phase - 1.0) * 2 * AZIMUTH_LIMIT
            // Automatic range scale: the smallest that keeps the farthest contact in the upper part of the scope.
            val farthest = out.maxOfOrNull { it.range } ?: 0.0
            val scale = SCALES.firstOrNull { it >= maxOf(250.0, farthest * 1.3) && it <= maxScale } ?: maxScale
            return RadarDisplayState(scale, heading, sweep, out.filter { it.range <= scale }, true)
        }
    }
}
