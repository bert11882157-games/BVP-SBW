package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** Free-air zeroing and range reticle, using the live discrete motion without terrain queries. */
object VehicleRangeBallistics {
    data class Solution(val direction: Vec3, val flightTicks: Double)

    /** Each discrete segment is affine in launch velocity. Solve its speed constraint exactly,
     * then verify with the forward model. No assumption about chassis roll or inherited velocity. */
    fun solve(snapshot: NominalShotSnapshot, target: Vec3,
        allowed: (Vec3) -> Boolean = { true }): Solution? {
        if (!valid(snapshot) || !finite(target)) return null
        val offset = target.subtract(snapshot.muzzle.position)
        if (offset.lengthSqr() < 1e-8 || offset.length() > snapshot.supportedMaxRangeBlocks) return null
        var coefficient = 0.0
        var velocityCoefficient = 1.0
        var gravityPosition = Vec3.ZERO
        var gravityVelocity = Vec3.ZERO
        val speed = snapshot.launchSpeedBlocksPerTick
        for (step in 0 until snapshot.horizonTicks) {
            val q = offset.subtract(gravityPosition).subtract(snapshot.inheritedPlatformMotion.scale(coefficient))
            val r = gravityVelocity.add(snapshot.inheritedPlatformMotion.scale(velocityCoefficient)).scale(-1.0)
            val a = r.lengthSqr() - speed * speed * velocityCoefficient * velocityCoefficient
            val b = 2.0 * (q.dot(r) - speed * speed * coefficient * velocityCoefficient)
            val c = q.lengthSqr() - speed * speed * coefficient * coefficient
            for (fraction in roots(a, b, c)) {
                if (fraction < 0.0 || fraction > 1.0 || step + fraction <= 1e-8) continue
                val scale = coefficient + velocityCoefficient * fraction
                if (scale <= 1e-8) continue
                val direction = q.add(r.scale(fraction)).scale(1.0 / (scale * speed)).normalize()
                if (!finite(direction) || !allowed(direction)) continue
                val shot = snapshot.copy(initialMotion = NominalProjectileMotion.initialMotion(direction, speed,
                    snapshot.inheritedPlatformMotion))
                val point = atTime(shot, step + fraction) ?: continue
                if (point.distanceToSqr(target) <= 0.0025) return Solution(direction, step + fraction)
            }
            coefficient += velocityCoefficient
            gravityPosition = gravityPosition.add(gravityVelocity)
            velocityCoefficient = NominalProjectileMotion.afterAirStep(Vec3(velocityCoefficient, 0.0, 0.0),
                0.0, snapshot.motionModel).x
            gravityVelocity = NominalProjectileMotion.afterAirStep(gravityVelocity,
                snapshot.gravityPerTick, snapshot.motionModel)
        }
        return null
    }

    /** Actual projectile crossing of the camera depth plane at the accepted zero range. */
    fun atRangePlane(snapshot: NominalShotSnapshot, cameraOrigin: Vec3, cameraDirection: Vec3,
        range: Double): Vec3? {
        if (!valid(snapshot) || !finite(cameraOrigin) || !finite(cameraDirection) ||
            cameraDirection.lengthSqr() < 1e-8 || !range.isFinite() || range <= 0.0) return null
        val normal = cameraDirection.normalize()
        val originDepth = snapshot.muzzle.position.subtract(cameraOrigin).dot(normal)
        val trajectory = NominalRelativeTrajectoryCache.get(snapshot)
        for (step in 0 until trajectory.stepCount) {
            val start = Vec3(trajectory.positionX[step], trajectory.positionY[step], trajectory.positionZ[step])
            val delta = Vec3(trajectory.motionX[step], trajectory.motionY[step], trajectory.motionZ[step])
            val depth = originDepth + start.dot(normal)
            val advance = delta.dot(normal)
            if (advance <= 1e-8) continue
            val fraction = (range - depth) / advance
            if (fraction in 0.0..1.0) return atTime(snapshot, step + fraction)
        }
        return null
    }

    internal fun atTime(snapshot: NominalShotSnapshot, ticks: Double): Vec3? {
        if (ticks <= 0.0 || !ticks.isFinite()) return null
        val trajectory = NominalRelativeTrajectoryCache.get(snapshot)
        val step = (kotlin.math.ceil(ticks).toInt() - 1).coerceAtLeast(0)
        if (step >= trajectory.stepCount || ticks > snapshot.horizonTicks ||
            (trajectory.ownerExpiredStep != 0 && step + 1 >= trajectory.ownerExpiredStep)) return null
        val fraction = ticks - step
        val travelled = (if (step == 0) 0.0 else trajectory.cumulativeTravel[step - 1]) +
            trajectory.segmentLength[step] * fraction
        if (travelled > snapshot.supportedMaxRangeBlocks) return null
        return snapshot.muzzle.position.add(trajectory.positionX[step] + trajectory.motionX[step] * fraction,
            trajectory.positionY[step] + trajectory.motionY[step] * fraction,
            trajectory.positionZ[step] + trajectory.motionZ[step] * fraction)
    }

    private fun roots(a: Double, b: Double, c: Double): List<Double> {
        if (abs(a) < 1e-12) return if (abs(b) < 1e-12) emptyList() else listOf(-c / b)
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0 || !discriminant.isFinite()) return emptyList()
        val q = -0.5 * (b + Math.copySign(sqrt(discriminant), b))
        return if (abs(q) < 1e-12) listOf(-b / (2.0 * a)) else listOf(q / a, c / q).sorted()
    }

    private fun valid(s: NominalShotSnapshot): Boolean = s.horizonTicks in 1..160 &&
        s.configuredLifetimeTicks >= 0 && s.horizonTicks <= s.configuredLifetimeTicks.toLong() + 1 &&
        s.launchSpeedBlocksPerTick.isFinite() && s.launchSpeedBlocksPerTick > 0 &&
        s.gravityPerTick.isFinite() && s.gravityPerTick >= 0 && finite(s.initialMotion) &&
        finite(s.muzzle.position) && finite(s.inheritedPlatformMotion) &&
        s.supportedMaxRangeBlocks.isFinite() && s.supportedMaxRangeBlocks in 0.0..4096.0 &&
        NominalProjectileModels.model(s.projectileTypeId)?.let {
            it.motion == s.motionModel && it.collision == s.collisionModel
        } == true

    private fun finite(v: Vec3) = v.x.isFinite() && v.y.isFinite() && v.z.isFinite()
}
