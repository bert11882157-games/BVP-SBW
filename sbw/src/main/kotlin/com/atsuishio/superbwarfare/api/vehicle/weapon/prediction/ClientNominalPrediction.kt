package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import net.minecraft.world.level.Level

/**
 * Work budget for one resumable client nominal-prediction slice.  A zero nanosecond budget
 * means no wall-clock limit; segment and voxel budgets may intentionally be zero to yield
 * without doing work. Segment and voxel limits are hard per-call bounds. The wall-clock limit
 * is enforced at each DDA voxel and vehicle-candidate checkpoint; one already-running shape
 * clip may finish before that checkpoint. It is never a formula or ballistic/path limit.
 */
data class NominalPredictionSliceBudget(
    val maxSegments: Int = 1,
    val maxVoxelVisits: Int = 64,
    val maxNanos: Long = 2_000_000L,
) {
    init {
        require(maxSegments >= 0) { "maxSegments must be non-negative" }
        require(maxVoxelVisits >= 0) { "maxVoxelVisits must be non-negative" }
        require(maxNanos >= 0L) { "maxNanos must be non-negative" }
    }
}

enum class NominalPredictionSliceStatus {
    IN_PROGRESS,
    COMPLETE,
    CANCELLED,
}

/**
 * Immutable client collision cursor.  The snapshot and Level identity are part of the cursor
 * contract; callers must cancel/restart when either changes rather than reusing stale work.
 */
data class NominalPredictionCursor internal constructor(
    val snapshot: NominalShotSnapshot,
    internal val level: Level,
    internal val trajectory: NominalRelativeTrajectoryCache.Trajectory,
    internal val segmentIndex: Int,
    internal val position: net.minecraft.world.phys.Vec3,
    internal val travelled: Double,
    internal val traceCursor: LoadedTraceCursor?,
    internal val sourceVehicle: com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity?,
    /** One immutable broadphase capture for the whole nominal path; never reused across ticks. */
    internal val vehicleCandidates: VehicleNominalCollision.Candidates?,
    internal val vehicleQueryCursor: VehicleNominalCollision.Cursor?,
    internal val vehicleQueryDone: Boolean,
    internal val vehicleHit: VehicleNominalCollision.Hit?,
)

data class NominalPredictionSlice(
    val status: NominalPredictionSliceStatus,
    val cursor: NominalPredictionCursor? = null,
    val result: NominalShotResult? = null,
    val segments: Int = 0,
    val voxelVisits: Int = 0,
)
