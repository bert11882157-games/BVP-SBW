package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** A hull point, never a camera offset: mouse movement cannot translate the orbit's pivot. */
object AircraftCameraPivot {
    fun center(vehicle: VehicleEntity, partial: Float): Vec3 {
        val body = vehicle.computed().aircraftTerrainContact?.fuselage
        val local = body?.minimum?.add(body.maximum)?.scale(.5) ?: Vec3(0.0, vehicle.bbHeight * .5, 0.0)
        val point = vehicle.getVehicleTransform(partial).transformPosition(Vector3d(local.x, local.y, local.z))
        return Vec3(point.x, point.y, point.z)
    }

    fun orbit(center: Vec3, yaw: Float, pitch: Float, distance: Double): Vec3 =
        center.subtract(Vec3.directionFromRotation(pitch, yaw).normalize().scale(distance))
}
