package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.*

/** Short-lived manual commands never acquire a target or read a laser designation. */
object AircraftManualCommand {
    private data class Sample(val controller: UUID, val tick: Long, val yaw: Int, val pitch: Int)
    private val inputs = WeakHashMap<VehicleEntity, Sample>()

    fun accept(vehicle: VehicleEntity, player: ServerPlayer, body: JsonObject, tick: Long) {
        require(player.vehicle === vehicle && vehicle.getSeatIndex(player) == AircraftMissileLaunchers.weaponSeat(vehicle))
        val yaw = body["Yaw"].asBigDecimal.intValueExact()
        val pitch = body["Pitch"].asBigDecimal.intValueExact()
        require(yaw in -1..1 && pitch in -1..1)
        val definition = AircraftArmamentManager.definition(vehicle) ?: return
        require(AircraftArmamentRegistry.mounts(definition).any { mount ->
            AircraftArmamentManager.equippedStore(vehicle,mount["Id"].asString)
                ?.getAsJsonObject("CommandGuidance")?.get("Mode")?.asString == "MCLOS"
        })
        inputs[vehicle] = Sample(player.uuid,tick,yaw,pitch)
    }

    fun direction(vehicle: VehicleEntity, player: ServerPlayer, current: Vec3,
                  referenceUp: Vec3, turnDegreesPerSecond: Double): Vec3 {
        val sample = inputs[vehicle] ?: return current
        if (!fresh(sample.controller,player.uuid,sample.tick,vehicle.level().gameTime) ||
            player.vehicle !== vehicle || !player.isAlive || player.isSpectator ||
            vehicle.isWreck || vehicle.isRemoved || vehicle.getSeatIndex(player) != 0) return current
        return steer(current,referenceUp,sample.yaw,sample.pitch,turnDegreesPerSecond/20.0)
    }

    internal fun fresh(controller: UUID, expected: UUID, tick: Long, now: Long) =
        controller == expected && now - tick in 0L..4L

    internal fun steer(current: Vec3, referenceUp: Vec3, yaw: Int, pitch: Int, degrees: Double): Vec3 {
        if (current.lengthSqr() < 1e-12 || !current.lengthSqr().isFinite() ||
            !degrees.isFinite() || degrees <= 0.0 || yaw == 0 && pitch == 0) return current
        val forward = current.normalize()
        var right = forward.cross(referenceUp).normalize()
        if (right.lengthSqr() < 1e-8) right = forward.cross(Vec3(0.0,0.0,1.0)).normalize()
        if (right.lengthSqr() < 1e-8) right = forward.cross(Vec3(1.0,0.0,0.0)).normalize()
        val up = right.cross(forward).normalize()
        val command = right.scale(yaw.coerceIn(-1,1).toDouble()).add(up.scale(pitch.coerceIn(-1,1).toDouble())).normalize()
        val angle = Math.toRadians(degrees.coerceAtMost(18.0))
        return forward.scale(cos(angle)).add(command.scale(sin(angle))).scale(current.length())
    }
}
