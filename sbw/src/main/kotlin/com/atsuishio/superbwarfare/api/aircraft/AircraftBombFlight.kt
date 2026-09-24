package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.api.vehicle.weapon.prediction.NominalProjectileMotion
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Bomb release and air motion shared by the live entity and the advisory impact predictor. */
object AircraftBombFlight {
    @JvmStatic fun launchOrigin(vehicle: VehicleEntity, local: Vec3): Vec3 {
        val point = vehicle.getVehicleTransform(1f).transformPosition(Vector3d(local.x, local.y, local.z))
        return Vec3(point.x, point.y, point.z)
    }

    @JvmStatic fun initialMotion(platformMotion: Vec3): Vec3 = platformMotion.add(0.0, -0.04, 0.0)

    /** Normal bombs retain their aircraft's forward speed; a deployed retarder loses it faster. */
    @JvmStatic fun horizontalDragFactor(multiplier: Double): Double =
        (1.0 - 0.0025 * multiplier.coerceIn(0.0, 20.0)).coerceIn(0.8, 1.0)

    @JvmStatic fun applyHorizontalDrag(motion: Vec3, multiplier: Double): Vec3 {
        val factor = horizontalDragFactor(multiplier)
        return Vec3(motion.x * factor, motion.y, motion.z * factor)
    }

    @JvmStatic fun afterPredictedAirStep(motion: Vec3, gravity: Double, multiplier: Double): Vec3 =
        applyHorizontalDrag(NominalProjectileMotion.afterFastThrowableAirStep(motion, gravity), multiplier)
}
