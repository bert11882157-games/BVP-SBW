package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import java.util.LinkedHashMap
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bounded exact-key cache for the pure, translation-independent part of forward prediction.
 * World collision remains uncached across simulation ticks. Client presentation may separately
 * reuse one complete collision result only for an identical sample in the same level tick.
 */
internal object NominalRelativeTrajectoryCache {
    private const val CACHE_SIZE = 64

    private data class OwnerKey(
        val relativeXBits: Long,
        val relativeYBits: Long,
        val relativeZBits: Long,
        val motionXBits: Long,
        val motionYBits: Long,
        val motionZBits: Long,
        val maximumDistanceBits: Long,
        val uncertaintyBits: Long,
    )

    private data class Key(
        val motionXBits: Long,
        val motionYBits: Long,
        val motionZBits: Long,
        val gravityBits: Long,
        val horizonTicks: Int,
        val motionModel: NominalMotionModel,
        val owner: OwnerKey?,
    )

    internal class Trajectory(
        val positionX: DoubleArray,
        val positionY: DoubleArray,
        val positionZ: DoubleArray,
        val motionX: DoubleArray,
        val motionY: DoubleArray,
        val motionZ: DoubleArray,
        val segmentLength: DoubleArray,
        val cumulativeTravel: DoubleArray,
        val stepCount: Int,
        val ownerExpiredStep: Int,
        val supportedRangeBlocks: Double,
    )

    private val trajectories = object : LinkedHashMap<Key, Trajectory>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Trajectory>?): Boolean =
            size > CACHE_SIZE
    }

    fun get(snapshot: NominalShotSnapshot): Trajectory {
        val ownerRelative = snapshot.ownerKinematics?.position?.subtract(snapshot.muzzle.position)
        val key = Key(
            snapshot.initialMotion.x.toRawBits(),
            snapshot.initialMotion.y.toRawBits(),
            snapshot.initialMotion.z.toRawBits(),
            snapshot.gravityPerTick.toRawBits(),
            snapshot.horizonTicks,
            snapshot.motionModel,
            snapshot.ownerKinematics?.let { owner ->
                val relative = requireNotNull(ownerRelative)
                OwnerKey(
                    relative.x.toRawBits(), relative.y.toRawBits(), relative.z.toRawBits(),
                    owner.motionPerTick.x.toRawBits(), owner.motionPerTick.y.toRawBits(),
                    owner.motionPerTick.z.toRawBits(), owner.maximumDistanceBlocks.toRawBits(),
                    owner.uncertaintyMarginBlocks.toRawBits(),
                )
            },
        )
        synchronized(trajectories) { trajectories[key] }?.let { return it }
        val built = build(snapshot, ownerRelative)
        synchronized(trajectories) {
            trajectories[key]?.let { return it }
            trajectories[key] = built
        }
        return built
    }

    private fun build(
        snapshot: NominalShotSnapshot,
        ownerRelative: net.minecraft.world.phys.Vec3?,
    ): Trajectory {
        val positionX = DoubleArray(snapshot.horizonTicks + 1)
        val positionY = DoubleArray(snapshot.horizonTicks + 1)
        val positionZ = DoubleArray(snapshot.horizonTicks + 1)
        val motionX = DoubleArray(snapshot.horizonTicks)
        val motionY = DoubleArray(snapshot.horizonTicks)
        val motionZ = DoubleArray(snapshot.horizonTicks)
        val segmentLength = DoubleArray(snapshot.horizonTicks)
        val cumulativeTravel = DoubleArray(snapshot.horizonTicks)
        var motion = snapshot.initialMotion
        var ownerX = ownerRelative?.x ?: 0.0
        var ownerY = ownerRelative?.y ?: 0.0
        var ownerZ = ownerRelative?.z ?: 0.0
        var path = 0.0
        var steps = 0
        var expiredStep = 0
        for (step in 1..snapshot.horizonTicks) {
            val index = step - 1
            motionX[index] = motion.x
            motionY[index] = motion.y
            motionZ[index] = motion.z
            val length = sqrt(motion.x * motion.x + motion.y * motion.y + motion.z * motion.z)
            segmentLength[index] = length
            positionX[step] = positionX[index] + motion.x
            positionY[step] = positionY[index] + motion.y
            positionZ[step] = positionZ[index] + motion.z
            path += length
            cumulativeTravel[index] = path
            steps = step
            snapshot.ownerKinematics?.let { owner ->
                ownerX += owner.motionPerTick.x
                ownerY += owner.motionPerTick.y
                ownerZ += owner.motionPerTick.z
                val threshold = owner.maximumDistanceBlocks - owner.uncertaintyMarginBlocks
                val dx = positionX[step] - ownerX
                val dy = positionY[step] - ownerY
                val dz = positionZ[step] - ownerZ
                if (threshold <= 0.0 || dx * dx + dy * dy + dz * dz > threshold * threshold) {
                    expiredStep = step
                }
            }
            if (!length.isFinite() || expiredStep != 0) break
            motion = NominalProjectileMotion.afterAirStep(
                motion, snapshot.gravityPerTick, snapshot.motionModel)
        }
        return Trajectory(
            positionX, positionY, positionZ, motionX, motionY, motionZ, segmentLength,
            cumulativeTravel, steps, expiredStep,
            min(path, VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS),
        )
    }
}
