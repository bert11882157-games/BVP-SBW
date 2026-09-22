package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.min

/** Internal resumable implementation shared by the public client cursor seam. */
internal object ClientNominalPredictionEngine {
    private const val SOURCE_LOOKUP_RADIUS_BLOCKS = 64.0

    fun begin(snapshot: NominalShotSnapshot, level: Level): NominalPredictionCursor =
        NominalPredictionCursor(
            snapshot,
            level,
            NominalRelativeTrajectoryCache.get(snapshot),
            0,
            snapshot.muzzle.position,
            0.0,
            null,
            findSourceVehicle(level, snapshot.vehicleUuid, snapshot.muzzle.position),
            null,
            null,
            false,
            null,
        )

    fun advance(
        cursor: NominalPredictionCursor,
        snapshot: NominalShotSnapshot,
        level: Level,
        budget: NominalPredictionSliceBudget,
    ): NominalPredictionSlice {
        if (cursor.snapshot != snapshot || cursor.level !== level) {
            return NominalPredictionSlice(
                NominalPredictionSliceStatus.CANCELLED,
                result = NominalShotResult(
                    NominalShotStatus.INVALID_CONTEXT,
                    diagnostic = NominalShotDiagnostic.PREDICTION_CANCELLED,
                ),
            )
        }
        val started = if (budget.maxNanos > 0L) System.nanoTime() else 0L
        val deadlineNanos = if (budget.maxNanos <= 0L) 0L
        else if (started <= Long.MAX_VALUE - budget.maxNanos) started + budget.maxNanos
        else Long.MAX_VALUE
        var current = cursor
        var segments = 0
        var voxelVisits = 0
        fun timedOut() = deadlineNanos != 0L && System.nanoTime() >= deadlineNanos
        fun pending() = NominalPredictionSlice(
            NominalPredictionSliceStatus.IN_PROGRESS,
            current,
            segments = segments,
            voxelVisits = voxelVisits,
        )
        fun complete(result: NominalShotResult) = NominalPredictionSlice(
            NominalPredictionSliceStatus.COMPLETE,
            result = result,
            segments = segments,
            voxelVisits = voxelVisits,
        )

        if (!finite(snapshot.muzzle.position) || !finite(snapshot.initialMotion) ||
            snapshot.ownerKinematics?.let { !finite(it.position) || !finite(it.motionPerTick) } == true
        ) return complete(NominalShotResult(
            NominalShotStatus.NON_FINITE,
            diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE,
        ))

        val sourceVehicle = current.sourceVehicle
        val resolvedSource = sourceVehicle?.takeIf { !it.isRemoved && it.level() === level }
            ?.let { level.getEntity(it.id) }
        if (sourceVehicle == null || resolvedSource !== sourceVehicle ||
            sourceVehicle.isRemoved || sourceVehicle.level() !== level ||
            sourceVehicle.uuid != snapshot.vehicleUuid
        ) return complete(NominalShotResult(
            NominalShotStatus.INVALID_CONTEXT,
            current.position,
            diagnostic = NominalShotDiagnostic.VEHICLE_CONTEXT_UNAVAILABLE,
        ))

        // Capture the complete path broadphase once. Segment work below only clips this
        // immutable candidate list, so agile-flight snapshots do not repeat the entity query
        // for every trajectory step and no world-collision result crosses a cursor boundary.
        if (current.vehicleCandidates == null) {
            val trajectory = current.trajectory
            val capture = VehicleNominalCollision.capturePath(
                level,
                sourceVehicle,
                snapshot.muzzle.position,
                trajectory.positionX,
                trajectory.positionY,
                trajectory.positionZ,
                trajectory.cumulativeTravel,
                trajectory.stepCount,
                snapshot.supportedMaxRangeBlocks,
                deadlineNanos,
            )
            if (capture.contextUnavailable) return complete(NominalShotResult(
                NominalShotStatus.INVALID_CONTEXT,
                current.position,
                diagnostic = NominalShotDiagnostic.VEHICLE_CONTEXT_UNAVAILABLE,
            ))
            current = current.copy(vehicleCandidates = capture.candidates)
            if (capture.candidates == null || capture.deadlineExceeded) return pending()
        }

        while (true) {
            if (timedOut()) return pending()
            val trajectory = current.trajectory
            if (current.segmentIndex >= trajectory.stepCount) {
                val exhausted = snapshot.configuredLifetimeTicks.toLong() + 1L > snapshot.horizonTicks.toLong()
                val last = (trajectory.stepCount - 1).coerceAtLeast(0)
                val direction = if (trajectory.stepCount == 0) null else finiteDirection(
                    trajectory.motionX[last], trajectory.motionY[last], trajectory.motionZ[last],
                )
                return complete(NominalShotResult(
                    if (exhausted) NominalShotStatus.BUDGET_EXCEEDED else NominalShotStatus.NO_IMPACT,
                    current.position,
                    direction,
                    snapshot.horizonTicks.toDouble(),
                    current.travelled,
                    snapshot.horizonTicks,
                    if (exhausted) NominalShotDiagnostic.PREDICTION_BUDGET_EXHAUSTED
                    else NominalShotDiagnostic.NO_BLOCK_WITHIN_LIFETIME,
                ))
            }

            val segmentIndex = current.segmentIndex
            val step = segmentIndex + 1
            val segmentLength = trajectory.segmentLength[segmentIndex]
            val motionX = trajectory.motionX[segmentIndex]
            val motionY = trajectory.motionY[segmentIndex]
            val motionZ = trajectory.motionZ[segmentIndex]
            val direction = finiteDirection(motionX, motionY, motionZ)
            if (!segmentLength.isFinite()) return complete(NominalShotResult(
                NominalShotStatus.NON_FINITE,
                current.position,
                direction,
                segmentIndex.toDouble(),
                current.travelled,
                segmentIndex,
                NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE,
            ))
            if (segmentLength <= 1.0E-9) return complete(NominalShotResult(
                NominalShotStatus.NO_IMPACT,
                current.position,
                direction,
                segmentIndex.toDouble(),
                current.travelled,
                segmentIndex,
                NominalShotDiagnostic.NO_BLOCK_WITHIN_LIFETIME,
            ))
            val remaining = snapshot.supportedMaxRangeBlocks - current.travelled
            if (remaining <= 0.0) return complete(NominalShotResult(
                NominalShotStatus.BUDGET_EXCEEDED,
                current.position,
                direction,
                segmentIndex.toDouble(),
                current.travelled,
                segmentIndex,
                NominalShotDiagnostic.PREDICTION_BUDGET_EXHAUSTED,
            ))
            val fraction = min(1.0, remaining / segmentLength)
            val end = if (fraction == 1.0) Vec3(
                snapshot.muzzle.position.x + trajectory.positionX[step],
                snapshot.muzzle.position.y + trajectory.positionY[step],
                snapshot.muzzle.position.z + trajectory.positionZ[step],
            ) else Vec3(
                current.position.x + motionX * fraction,
                current.position.y + motionY * fraction,
                current.position.z + motionZ * fraction,
            )

            var vehicleHit = current.vehicleHit
            if (!current.vehicleQueryDone) {
                var vehicleCursor = current.vehicleQueryCursor
                if (vehicleCursor == null) {
                    if (segments >= budget.maxSegments) return pending()
                    val startedQuery = VehicleNominalCollision.beginSegment(
                        level, current.vehicleCandidates, current.position, end, deadlineNanos,
                    )
                    if (startedQuery.contextUnavailable) {
                        return complete(NominalShotResult(
                            NominalShotStatus.INVALID_CONTEXT,
                            current.position,
                            direction,
                            segmentIndex.toDouble(),
                            current.travelled,
                            segmentIndex,
                            NominalShotDiagnostic.VEHICLE_CONTEXT_UNAVAILABLE,
                        ))
                    }
                    if (startedQuery.deadlineExceeded) {
                        current = current.copy(vehicleQueryCursor = startedQuery.cursor)
                        return pending()
                    }
                    vehicleCursor = startedQuery.cursor
                    if (vehicleCursor == null) return complete(NominalShotResult(
                        NominalShotStatus.INVALID_CONTEXT,
                        current.position,
                        direction,
                        segmentIndex.toDouble(),
                        current.travelled,
                        segmentIndex,
                        NominalShotDiagnostic.VEHICLE_CONTEXT_UNAVAILABLE,
                    ))
                    current = current.copy(vehicleQueryCursor = vehicleCursor)
                    segments++
                }
                val vehicleAdvance = VehicleNominalCollision.advance(
                    level, vehicleCursor, deadlineNanos,
                )
                val vehicleQuery = vehicleAdvance.result
                if (vehicleQuery == null) {
                    current = current.copy(vehicleQueryCursor = vehicleAdvance.cursor)
                    return pending()
                }
                if (vehicleQuery.contextUnavailable) {
                    return complete(NominalShotResult(
                        NominalShotStatus.INVALID_CONTEXT,
                        current.position,
                        direction,
                        segmentIndex.toDouble(),
                        current.travelled,
                        segmentIndex,
                        NominalShotDiagnostic.VEHICLE_CONTEXT_UNAVAILABLE,
                    ))
                }
                vehicleHit = vehicleQuery.hit
                current = current.copy(
                    vehicleQueryCursor = null,
                    vehicleQueryDone = true,
                    vehicleHit = vehicleHit,
                )
                if (timedOut()) return pending()
            }
            var traceCursor = current.traceCursor
            if (traceCursor == null) {
                traceCursor = LoadedChunkBlockTrace.begin(
                    current.position, end, snapshot.collisionModel,
                    detectLiquid = true, rejectContextSensitive = true,
                )
                current = current.copy(traceCursor = traceCursor)
                if (timedOut()) return pending()
            }
            val availableVoxels = budget.maxVoxelVisits - voxelVisits
            if (availableVoxels <= 0) return pending().copy(cursor = current.copy(traceCursor = traceCursor))
            val trace = LoadedChunkBlockTrace.advance(
                level, traceCursor, availableVoxels, deadlineNanos,
            )
            voxelVisits += trace.visited
            val traceResult = trace.result
            if (traceResult == null) {
                current = current.copy(traceCursor = requireNotNull(trace.cursor))
                continue
            }
            val tracePoint = traceResult.frontier
            if (!finite(tracePoint)) return complete(NominalShotResult(
                NominalShotStatus.NON_FINITE,
                current.position,
                direction,
                segmentIndex.toDouble(),
                current.travelled,
                segmentIndex,
                NominalShotDiagnostic.TRACE_RESULT_NON_FINITE,
            ))
            val eventDistance = current.position.distanceTo(tracePoint)
            val traceFraction = traceResult.completedFraction.coerceIn(0.0, 1.0)
            if (vehicleHit != null && vehicleHit.fraction + 1.0E-9 < traceFraction) {
                return complete(eventResult(
                    NominalShotStatus.IMPACT,
                    vehicleHit.point,
                    direction,
                    segmentIndex,
                    segmentLength * vehicleHit.fraction,
                    segmentLength,
                    current.travelled,
                    step,
                    NominalShotDiagnostic.FIRST_VEHICLE_COLLISION,
                ))
            }
            when (traceResult.event) {
                LoadedTraceEvent.LIQUID -> return complete(eventResult(
                    NominalShotStatus.WATER_SURFACE_IMPACT, tracePoint, direction,
                    segmentIndex, eventDistance, segmentLength, current.travelled, step,
                    NominalShotDiagnostic.FIRST_WATER_SURFACE_CONTACT,
                ))
                LoadedTraceEvent.CONTEXT_SENSITIVE -> return complete(eventResult(
                    NominalShotStatus.UNSUPPORTED, tracePoint, direction,
                    segmentIndex, eventDistance, segmentLength, current.travelled, step,
                    NominalShotDiagnostic.TRACE_CONTEXT_SENSITIVE,
                ))
                LoadedTraceEvent.UNLOADED -> return complete(eventResult(
                    NominalShotStatus.UNLOADED_TERRAIN, tracePoint, direction,
                    segmentIndex, eventDistance, segmentLength, current.travelled, segmentIndex,
                    NominalShotDiagnostic.TRACE_UNLOADED,
                ))
                LoadedTraceEvent.BLOCK -> {
                    val hit = requireNotNull(traceResult.hit)
                    val hitDistance = current.position.distanceTo(hit.location)
                    return complete(eventResult(
                        NominalShotStatus.IMPACT, hit.location, direction,
                        segmentIndex, hitDistance, segmentLength, current.travelled, step,
                        NominalShotDiagnostic.NONE,
                    ))
                }
                LoadedTraceEvent.CLEAR -> {
                    if (vehicleHit != null) return complete(eventResult(
                        NominalShotStatus.IMPACT,
                        vehicleHit.point,
                        direction,
                        segmentIndex,
                        segmentLength * vehicleHit.fraction,
                        segmentLength,
                        current.travelled,
                        step,
                        NominalShotDiagnostic.FIRST_VEHICLE_COLLISION,
                    ))
                }
            }

            val previousTravel = if (segmentIndex == 0) 0.0
            else trajectory.cumulativeTravel[segmentIndex - 1]
            val travelled = previousTravel + segmentLength * fraction
            if (fraction < 1.0) return complete(NominalShotResult(
                NominalShotStatus.BUDGET_EXCEEDED,
                end,
                direction,
                segmentIndex + fraction,
                travelled,
                step,
                NominalShotDiagnostic.PREDICTION_BUDGET_EXHAUSTED,
            ))
            current = current.copy(
                segmentIndex = step,
                position = end,
                travelled = travelled,
                traceCursor = null,
                vehicleQueryCursor = null,
                vehicleQueryDone = false,
                vehicleHit = null,
            )
            snapshot.ownerKinematics?.let { owner ->
                if (trajectory.ownerExpiredStep == step) {
                    return complete(NominalShotResult(
                        if (owner.uncertaintyMarginBlocks > 0.0) NominalShotStatus.UNSUPPORTED
                        else NominalShotStatus.NO_IMPACT,
                        end,
                        direction,
                        step.toDouble(),
                        travelled,
                        step,
                        NominalShotDiagnostic.OWNER_DISTANCE_EXPIRED,
                    ))
                }
            }
            if (!finite(end)) return complete(NominalShotResult(
                NominalShotStatus.NON_FINITE,
                steps = step,
                diagnostic = NominalShotDiagnostic.LAUNCH_STATE_NON_FINITE,
            ))
        }
    }

    private fun eventResult(
        status: NominalShotStatus,
        point: Vec3,
        direction: Vec3?,
        segmentIndex: Int,
        distance: Double,
        segmentLength: Double,
        travelled: Double,
        steps: Int,
        diagnostic: NominalShotDiagnostic,
    ) = NominalShotResult(
        status,
        point,
        direction,
        segmentIndex + distance / segmentLength,
        travelled + distance,
        steps,
        diagnostic,
    )

    private fun finite(value: Vec3): Boolean =
        value.x.isFinite() && value.y.isFinite() && value.z.isFinite()

    private fun finiteDirection(x: Double, y: Double, z: Double): Vec3? =
        Vec3(x, y, z).takeIf { finite(it) && it.lengthSqr() > 1.0E-12 }?.normalize()

    /**
     * Level#getEntity is integer-ID based in the mapped client API. Resolve by UUID through a
     * small spatial query instead, and fail closed on a duplicate UUID or a missing match.
     * The query is deliberately local to the launch/current vehicle anchor; it never scans the
     * world and keeps stale entity/UUID reuse checks identity-based.
     */
    private fun findSourceVehicle(level: Level, uuid: UUID, anchor: Vec3): VehicleEntity? {
        if (!finite(anchor)) return null
        val r = SOURCE_LOOKUP_RADIUS_BLOCKS
        val bounds = AABB(
            anchor.x - r, anchor.y - r, anchor.z - r,
            anchor.x + r, anchor.y + r, anchor.z + r,
        )
        var match: VehicleEntity? = null
        for (candidate in level.getEntitiesOfClass(VehicleEntity::class.java, bounds)) {
            if (candidate.uuid != uuid) continue
            if (match != null && match !== candidate) return null
            match = candidate
        }
        return match
    }
}
