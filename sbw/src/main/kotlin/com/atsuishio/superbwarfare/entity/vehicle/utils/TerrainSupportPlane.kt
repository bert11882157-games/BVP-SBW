package com.atsuishio.superbwarfare.entity.vehicle.utils

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseProvider
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import org.joml.Matrix4d

/** Least-squares height gradient, independent of contact order and chassis-origin placement. */
object TerrainSupportPlane {
    /** Contacts pair model-local wheel bottoms with terrain height relative to entity origin. */
    fun groundBias(contacts: List<Pair<Vec3, Double>>, pitch: Double, roll: Double, pivot: Double): Double {
        if (contacts.size < 3) return 0.0
        val transform=Matrix4d().translate(0.0,pivot,0.0)
            .rotateX(Math.toRadians(pitch)).rotateZ(Math.toRadians(roll)).translate(0.0,-pivot,0.0)
        val upY=transform.transformDirection(org.joml.Vector3d(0.0,1.0,0.0)).y
        if (upY < 0.35) return 0.0
        return contacts.maxOf { (local, ground) ->
            val point=transform.transformPosition(org.joml.Vector3d(local.x,local.y,local.z))
            (ground-point.y)/upY
        }.coerceIn(-0.75,0.75)
    }
    fun fit(points: List<Vec3>): Vec3? {
        if (points.size < 3 || points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null
        val x = points.sumOf { it.x } / points.size
        val y = points.sumOf { it.y } / points.size
        val z = points.sumOf { it.z } / points.size
        var xx=0.0; var zz=0.0; var xz=0.0; var xy=0.0; var zy=0.0
        for (p in points) {
            val dx=p.x-x; val dy=p.y-y; val dz=p.z-z
            xx+=dx*dx; zz+=dz*dz; xz+=dx*dz; xy+=dx*dy; zy+=dz*dy
        }
        val determinant=xx*zz-xz*xz
        if (abs(determinant)<1e-9) return null
        return Vec3((xy*zz-zy*xz)/determinant, 0.0, (zy*xx-xy*xz)/determinant)
    }
}

/** Existing synchronized chassis-pose channel keeps geometry, hitboxes, camera and riders together. */
class TerrainSupportPose(private val vehicle: VehicleEntity) : VehiclePoseProvider {
    var contacts: List<Pair<Vec3, Double>> = emptyList()
    override fun usesLegacyBasePose() = true
    override fun updateVehiclePose(previous: VehiclePoseSnapshot): VehiclePoseSnapshot {
        val bias = if (vehicle.onGround()) TerrainSupportPlane.groundBias(contacts,
            vehicle.xRot.toDouble(),vehicle.roll.toDouble(),vehicle.rotateOffsetHeight)
            else previous.groundBias * 0.75
        return VehiclePoseSnapshot.createComponents(0f,0f,bias,0.0,0.0)
    }
}
