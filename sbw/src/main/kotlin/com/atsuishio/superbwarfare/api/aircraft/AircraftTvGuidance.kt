package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.AircraftArmamentNetwork
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

/**
 * Television guidance (owner's brief, 2026-09-28): after release the pilot sees through the munition's seeker and
 * steers it by moving the crosshair; the munition flies at the point under the crosshair. When the pilot leaves the
 * seeker view (or the link is lost) the seeker stays locked on the last point, and on a vehicle it keeps tracking
 * that vehicle, so a TV weapon works both man-in-the-loop (GBU-15, KD-88) and lock-and-leave (AGM-65B, Kh-25MT,
 * KAB-500Kr).
 *
 * Server authority: the client only sends the seeker's line of sight (and the seeker position it drew). The server
 * checks the sender is this munition's operator, the position is the munition's and the line lies inside the
 * seeker's gimbal, then lases along it through loaded terrain and vehicles to find the aim point.
 */
object AircraftTvGuidance {
    const val OPERATOR = "BvpTvOperator"
    const val AIRCRAFT = "BvpTvAircraft"
    private const val POINT = "BvpTvPoint"
    private const val TRACK = "BvpTvTrack"
    private const val TRACK_OFFSET = "BvpTvTrackOffset"
    /** Seeker line of sight range; the munition's own surroundings are loaded while it flies. */
    const val RANGE = 2048.0
    /** Largest angle between the munition's flight path and the seeker line it accepts. */
    const val GIMBAL_DEGREES = 70.0
    private val GIMBAL_COSINE = kotlin.math.cos(Math.toRadians(GIMBAL_DEGREES + 5.0))

    fun isTv(munition: Entity): Boolean = munition.persistentData.hasUUID(OPERATOR)

    /** Hands [munition] to [operator]'s seeker view; [track] (a locked vehicle) or [point] is the first aim point. */
    fun attach(munition: Entity, aircraft: VehicleEntity, operator: ServerPlayer, track: Entity? = null, point: Vec3? = null) {
        val data = munition.persistentData
        data.putUUID(OPERATOR, operator.uuid)
        data.putUUID(AIRCRAFT, aircraft.uuid)
        val vehicle = track as? VehicleEntity
        if (vehicle != null) setTrack(munition, vehicle, vehicle.boundingBox.center)
        else (track?.boundingBox?.center ?: point)?.let { setPoint(munition, it) }
    }

    /** Tells the operator's client to open the seeker view of [munition] (after it has been spawned). */
    fun openView(munition: Entity, aircraft: VehicleEntity, operator: ServerPlayer) {
        AircraftArmamentNetwork.send(operator, JsonObject().apply {
            addProperty("Vehicle", aircraft.uuid.toString())
            addProperty("EntityId", aircraft.id)
            addProperty("Dimension", aircraft.level().dimension().location().toString())
            addProperty("TvMunition", munition.id)
            aimPoint(munition)?.let { p -> add("TvPoint", JsonArray().apply { add(p.x); add(p.y); add(p.z) }) }
        })
    }

    /** One operator command ("TV" request): {Entity, Origin, Direction}. */
    fun command(player: ServerPlayer, aircraft: VehicleEntity, body: JsonObject) {
        val level = player.serverLevel()
        val munition = level.getEntity(body["Entity"].asInt) ?: return
        val data = munition.persistentData
        if (munition.isRemoved || !data.hasUUID(OPERATOR) || data.getUUID(OPERATOR) != player.uuid ||
            !data.hasUUID(AIRCRAFT) || data.getUUID(AIRCRAFT) != aircraft.uuid) return
        val direction = requireNotNull(AircraftArmamentRegistry.vector(body["Direction"])) { "Invalid seeker direction." }
        require(direction.lengthSqr() in 0.98..1.02) { "Invalid seeker direction." }
        val line = direction.normalize()
        val heading = munition.deltaMovement.takeIf { it.lengthSqr() > 1.0E-6 }?.normalize() ?: munition.lookAngle
        if (line.dot(heading) < GIMBAL_COSINE) return
        // The client draws the seeker a little ahead of the munition; lase from the point it drew when that is
        // plausibly this munition's, so the crosshair and the aim point agree.
        val slack = 12.0 + munition.deltaMovement.length() * 3.0
        val origin = AircraftArmamentRegistry.vector(body["Origin"])?.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.distanceToSqr(munition.position()) <= slack * slack
        } ?: munition.position()
        val laser = VehicleLaserRangefinder.measureReturn(level, munition, origin, line, RANGE)
        if (laser == null) {
            // Nothing within range along the line (sky, or unloaded ground): aim far along it; the munition flies
            // the line until the crosshair finds ground.
            setPoint(munition, origin.add(line.scale(RANGE)))
            return
        }
        val point = origin.add(line.scale(laser.distance))
        val vehicle = laser.vehicle
        if (vehicle != null && vehicle.isAlive && !vehicle.isWreck) setTrack(munition, vehicle, point) else setPoint(munition, point)
    }

    /** Where the munition's seeker aims now: on the tracked vehicle while it is there, else the stored point. */
    @JvmStatic fun aimPoint(munition: Entity): Vec3? {
        val data = munition.persistentData
        val level = munition.level() as? ServerLevel
        if (level != null && data.hasUUID(TRACK) && data.contains(TRACK_OFFSET, 10)) {
            val vehicle = level.getEntity(data.getUUID(TRACK)) as? VehicleEntity
            if (vehicle != null && vehicle.isAlive && !vehicle.isRemoved && !vehicle.isWreck) {
                AircraftDesignationData.worldPoint(vehicle, read(data.getCompound(TRACK_OFFSET)) ?: return point(data))
                    ?.let { return it }
            }
        }
        return point(data)
    }

    /** The vehicle the seeker is tracking, while it is loaded and alive. */
    fun trackedVehicle(munition: Entity): java.util.UUID? {
        val data = munition.persistentData
        return if (data.hasUUID(TRACK)) data.getUUID(TRACK) else null
    }

    private fun setPoint(munition: Entity, point: Vec3) {
        val data = munition.persistentData
        data.remove(TRACK); data.remove(TRACK_OFFSET)
        data.put(POINT, write(point))
    }

    private fun setTrack(munition: Entity, vehicle: VehicleEntity, point: Vec3) {
        val data = munition.persistentData
        val offset = AircraftDesignationData.localPoint(vehicle, point) ?: return setPoint(munition, point)
        data.putUUID(TRACK, vehicle.uuid)
        data.put(TRACK_OFFSET, write(offset))
        data.put(POINT, write(point))
    }

    private fun point(data: CompoundTag): Vec3? = if (data.contains(POINT, 10)) read(data.getCompound(POINT)) else null

    private fun write(v: Vec3) = CompoundTag().apply { putDouble("X", v.x); putDouble("Y", v.y); putDouble("Z", v.z) }

    private fun read(tag: CompoundTag): Vec3? = Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"))
        .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
}
