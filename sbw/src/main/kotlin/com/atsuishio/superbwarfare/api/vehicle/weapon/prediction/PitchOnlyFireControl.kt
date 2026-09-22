package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Opaque server-side admission supplied by the HasFCS G acquisition path.  It is deliberately
 * not serializable and cannot be inferred from a weapon name, projectile, or client request.
 */
class HasFcsGAcquisition private constructor() {
    companion object {
        @JvmField
        val INSTANCE: HasFcsGAcquisition = HasFcsGAcquisition()

        @JvmStatic
        fun valid(): HasFcsGAcquisition = INSTANCE
    }
}

/** Fixed-yaw direction family.  The callback must return a world direction for one elevation. */
fun interface PitchOnlyDirectionFamily {
    fun directionAtElevationDegrees(elevationDegrees: Double): Vec3?
}

enum class PitchOnlySolveStatus {
    SOLUTION,
    NO_SOLUTION,
    UNSUPPORTED,
    INVALID_INPUT,
    NON_FINITE,
    BUDGET_EXCEEDED,
}

enum class PitchOnlySolveDiagnostic {
    NONE,
    ACQUISITION_REQUIRED,
    SNAPSHOT_UNSUPPORTED,
    TARGET_NON_FINITE,
    RANGE_INVALID,
    BOUNDS_INVALID,
    FAMILY_INVALID,
    NO_REACHABLE_CROSSING,
    RESIDUAL_EXCEEDED,
    EVALUATION_BUDGET,
}

/** Immutable result of a bounded pitch-only nominal solve. */
data class PitchOnlyFireControlResult(
    val status: PitchOnlySolveStatus,
    val correctedWorldDirection: Vec3? = null,
    val elevationDegrees: Double? = null,
    val impactPoint: Vec3? = null,
    val timeOfFlightTicks: Double? = null,
    val travelledDistanceBlocks: Double = 0.0,
    val verticalResidualBlocks: Double = Double.NaN,
    val lateralResidualBlocks: Double = Double.NaN,
    val evaluatedCandidates: Int = 0,
    val diagnostic: PitchOnlySolveDiagnostic = PitchOnlySolveDiagnostic.NONE,
)

/**
 * Pure, bounded inverse helper for a fixed local-yaw elevation family.  It intentionally has no
 * world, raycast, entity, packet, camera, or firing dependency: Physics supplies the already
 * authoritative first-BLOCK target and family, and consumes only a verified direction.
 */
object PitchOnlyFireControl {
    private const val COARSE_INTERVALS = 128
    private const val MAX_EVALUATIONS = 384
    private const val REFINE_ITERATIONS = 24
    private const val VERTICAL_TOLERANCE_BLOCKS = 0.05
    private const val LATERAL_TOLERANCE_BLOCKS = 0.5
    private const val TANGENT_EPSILON_BLOCKS = 1.0E-9
    private const val DIRECTION_EPSILON_SQ = 1.0E-12

    private data class CandidateEvaluation(
        val angle: Double,
        val direction: Vec3,
        val point: Vec3,
        val timeOfFlight: Double,
        val travelled: Double,
        val verticalError: Double,
        val lateralError: Double,
    )

    /** Convenience overload: the finite target point supplies the target range. */
    @JvmStatic
    fun solveFreeAirPitchOnly(
        snapshot: NominalShotSnapshot,
        targetPoint: Vec3,
        family: PitchOnlyDirectionFamily,
        minElevationDegrees: Double,
        maxElevationDegrees: Double,
        acquisition: HasFcsGAcquisition?,
    ): PitchOnlyFireControlResult {
        if (!finite(targetPoint) || !finite(snapshot.muzzle.position)) {
            return failure(PitchOnlySolveStatus.NON_FINITE, PitchOnlySolveDiagnostic.TARGET_NON_FINITE)
        }
        val range = snapshot.muzzle.position.distanceTo(targetPoint)
        return solveFreeAirPitchOnly(
            snapshot,
            targetPoint,
            range,
            family,
            minElevationDegrees,
            maxElevationDegrees,
            acquisition,
        )
    }

    /**
     * Solves only elevation while preserving the caller's fixed-yaw direction family.  The
     * optional explicit range is the authoritative muzzle-to-target distance and is validated
     * independently; targetPoint remains the exact vertical/lateral constraint.
     */
    @JvmStatic
    fun solveFreeAirPitchOnly(
        snapshot: NominalShotSnapshot,
        targetPoint: Vec3,
        targetRangeBlocks: Double,
        family: PitchOnlyDirectionFamily,
        minElevationDegrees: Double,
        maxElevationDegrees: Double,
        acquisition: HasFcsGAcquisition?,
    ): PitchOnlyFireControlResult {
        if (acquisition !== HasFcsGAcquisition.INSTANCE) {
            return failure(PitchOnlySolveStatus.UNSUPPORTED, PitchOnlySolveDiagnostic.ACQUISITION_REQUIRED)
        }
        if (!finite(snapshot.muzzle.position) || !finite(snapshot.initialMotion) ||
            !finite(snapshot.inheritedPlatformMotion) || snapshot.launchSpeedBlocksPerTick <= 0.0 ||
            !snapshot.launchSpeedBlocksPerTick.isFinite() || !snapshot.gravityPerTick.isFinite() ||
            snapshot.gravityPerTick < 0.0 || snapshot.horizonTicks !in 1..VehicleShotPredictionService.MAX_PREDICTION_TICKS ||
            snapshot.configuredLifetimeTicks < 0 || !snapshot.supportedMaxRangeBlocks.isFinite() ||
            snapshot.supportedMaxRangeBlocks <= 0.0 || snapshot.ownerKinematics?.let {
                !finite(it.position) || !finite(it.motionPerTick) ||
                    !it.maximumDistanceBlocks.isFinite() || it.maximumDistanceBlocks <= 0.0 ||
                    !it.uncertaintyMarginBlocks.isFinite() || it.uncertaintyMarginBlocks < 0.0
            } == true
        ) {
            return failure(PitchOnlySolveStatus.NON_FINITE, PitchOnlySolveDiagnostic.SNAPSHOT_UNSUPPORTED)
        }
        val model = NominalProjectileModels.model(snapshot.projectileTypeId)
            ?: return failure(PitchOnlySolveStatus.UNSUPPORTED, PitchOnlySolveDiagnostic.SNAPSHOT_UNSUPPORTED)
        if (model.motion != snapshot.motionModel || model.collision != snapshot.collisionModel) {
            return failure(PitchOnlySolveStatus.UNSUPPORTED, PitchOnlySolveDiagnostic.SNAPSHOT_UNSUPPORTED)
        }
        if (!finite(targetPoint)) {
            return failure(PitchOnlySolveStatus.NON_FINITE, PitchOnlySolveDiagnostic.TARGET_NON_FINITE)
        }
        if (!targetRangeBlocks.isFinite() || targetRangeBlocks <= 0.0 ||
            targetRangeBlocks > VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS
        ) {
            return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.RANGE_INVALID)
        }
        if (!minElevationDegrees.isFinite() || !maxElevationDegrees.isFinite() ||
            minElevationDegrees > maxElevationDegrees
        ) {
            return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.BOUNDS_INVALID)
        }

        val origin = snapshot.muzzle.position
        val targetOffset = targetPoint.subtract(origin)
        if (!finite(targetOffset)) {
            return failure(PitchOnlySolveStatus.NON_FINITE, PitchOnlySolveDiagnostic.TARGET_NON_FINITE)
        }
        val pointRange = origin.distanceTo(targetPoint)
        if (!pointRange.isFinite() || abs(pointRange - targetRangeBlocks) > 1.0E-3) {
            return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.RANGE_INVALID)
        }
        val horizontalRange = hypot(targetOffset.x, targetOffset.z)
        if (!horizontalRange.isFinite() || horizontalRange <= 1.0E-9) {
            return failure(PitchOnlySolveStatus.NO_SOLUTION, PitchOnlySolveDiagnostic.NO_REACHABLE_CROSSING)
        }
        val bearingX = targetOffset.x / horizontalRange
        val bearingZ = targetOffset.z / horizontalRange
        val fixedDirection = normalizedDirectionAt(family, 0.0)
            ?: return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.FAMILY_INVALID)
        val fixedHorizontal = hypot(fixedDirection.x, fixedDirection.z)
        if (!fixedHorizontal.isFinite() || fixedHorizontal <= 1.0E-9) {
            return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.FAMILY_INVALID)
        }
        val fixedBearingX = fixedDirection.x / fixedHorizontal
        val fixedBearingZ = fixedDirection.z / fixedHorizontal
        val bearingCross = abs(-fixedBearingZ * bearingX + fixedBearingX * bearingZ)
        val bearingDot = fixedBearingX * bearingX + fixedBearingZ * bearingZ
        if (!bearingCross.isFinite() || !bearingDot.isFinite() || bearingCross > 1.0E-6 || bearingDot <= 0.0) {
            return failure(PitchOnlySolveStatus.INVALID_INPUT, PitchOnlySolveDiagnostic.FAMILY_INVALID)
        }

        val evaluations = HashMap<Long, CandidateEvaluation?>()
        var evaluationCount = 0
        var budgetExceeded = false

        fun evaluate(angle: Double): CandidateEvaluation? {
            val key = angle.toRawBits()
            if (evaluations.containsKey(key)) return evaluations[key]
            if (evaluationCount >= MAX_EVALUATIONS) {
                budgetExceeded = true
                return null
            }
            evaluationCount++
            val direction = normalizedDirectionAt(family, angle)
            if (direction == null) {
                evaluations[key] = null
                return null
            }
            val candidateHorizontal = hypot(direction.x, direction.z)
            if (!candidateHorizontal.isFinite() || candidateHorizontal <= 1.0E-9) {
                evaluations[key] = null
                return null
            }
            val candidateBearingX = direction.x / candidateHorizontal
            val candidateBearingZ = direction.z / candidateHorizontal
            val candidateCross = abs(-fixedBearingZ * candidateBearingX + fixedBearingX * candidateBearingZ)
            val candidateDot = fixedBearingX * candidateBearingX + fixedBearingZ * candidateBearingZ
            if (!candidateCross.isFinite() || !candidateDot.isFinite() || candidateCross > 1.0E-6 || candidateDot <= 0.0) {
                evaluations[key] = null
                return null
            }
            val candidate = snapshot.copy(
                muzzle = snapshot.muzzle.copy(direction = direction),
                initialMotion = NominalProjectileMotion.initialMotion(
                    direction,
                    snapshot.launchSpeedBlocksPerTick,
                    snapshot.inheritedPlatformMotion,
                ),
            )
            if (!finite(candidate.initialMotion)) {
                evaluations[key] = null
                return null
            }
            val trajectory = NominalRelativeTrajectoryCache.get(candidate)
            for (step in 0 until trajectory.stepCount) {
                if (trajectory.ownerExpiredStep != 0 && step + 1 > trajectory.ownerExpiredStep) break
                val segment = trajectory.segmentLength[step]
                if (!segment.isFinite() || segment <= 1.0E-9) {
                    continue
                }
                val x0 = trajectory.positionX[step]
                val y0 = trajectory.positionY[step]
                val z0 = trajectory.positionZ[step]
                val x1 = trajectory.positionX[step + 1]
                val y1 = trajectory.positionY[step + 1]
                val z1 = trajectory.positionZ[step + 1]
                val along0 = x0 * bearingX + z0 * bearingZ
                val along1 = x1 * bearingX + z1 * bearingZ
                val delta = along1 - along0
                if (!delta.isFinite() || abs(delta) <= 1.0E-12) {
                    continue
                }
                val fraction = (horizontalRange - along0) / delta
                if (!fraction.isFinite() || fraction < -1.0E-9 || fraction > 1.0 + 1.0E-9) {
                    continue
                }
                val t = fraction.coerceIn(0.0, 1.0)
                val point = Vec3(
                    origin.x + x0 + (x1 - x0) * t,
                    origin.y + y0 + (y1 - y0) * t,
                    origin.z + z0 + (z1 - z0) * t,
                )
                if (!finite(point)) {
                    evaluations[key] = null
                    return null
                }
                val lateral = abs(-bearingZ * (x0 + (x1 - x0) * t) + bearingX * (z0 + (z1 - z0) * t))
                val pathBefore = if (step == 0) 0.0 else trajectory.cumulativeTravel[step - 1]
                val travelled = pathBefore + segment * t
                if (!lateral.isFinite() || !travelled.isFinite() || travelled > VehicleShotPredictionService.MAX_PREDICTION_PATH_BLOCKS + 1.0E-9) {
                    evaluations[key] = null
                    return null
                }
                val evaluation = CandidateEvaluation(
                    angle,
                    direction,
                    point,
                    step.toDouble() + t,
                    travelled,
                    point.y - targetPoint.y,
                    lateral,
                )
                evaluations[key] = evaluation
                return evaluation
            }
            evaluations[key] = null
            return null
        }

        fun acceptable(evaluation: CandidateEvaluation?): Boolean {
            if (evaluation == null || !evaluation.verticalError.isFinite() || !evaluation.lateralError.isFinite()) return false
            return evaluation.verticalError in -VERTICAL_TOLERANCE_BLOCKS..VERTICAL_TOLERANCE_BLOCKS &&
                evaluation.lateralError <= LATERAL_TOLERANCE_BLOCKS
        }

        fun asResult(evaluation: CandidateEvaluation): PitchOnlyFireControlResult {
            return PitchOnlyFireControlResult(
                PitchOnlySolveStatus.SOLUTION,
                evaluation.direction,
                evaluation.angle,
                evaluation.point,
                evaluation.timeOfFlight,
                evaluation.travelled,
                evaluation.verticalError,
                evaluation.lateralError,
                evaluationCount,
            )
        }

        fun forwardVerify(angle: Double): PitchOnlyFireControlResult? {
            val evaluation = evaluate(angle) ?: return null
            if (!acceptable(evaluation)) return null
            return asResult(evaluation)
        }

        fun refine(left: CandidateEvaluation, right: CandidateEvaluation): PitchOnlyFireControlResult? {
            var lo = left
            var hi = right
            var best: CandidateEvaluation? = null
            if (acceptable(lo) && lo.verticalError <= 0.0) best = lo
            if (acceptable(hi) && hi.verticalError <= 0.0 && (best == null || hi.angle < best!!.angle)) best = hi
            repeat(REFINE_ITERATIONS) {
                val midAngle = (lo.angle + hi.angle) * 0.5
                if (!midAngle.isFinite() || midAngle == lo.angle || midAngle == hi.angle) return@repeat
                val mid = evaluate(midAngle) ?: return@repeat
                if (acceptable(mid) && (best == null || mid.angle < best!!.angle ||
                        (abs(mid.angle - best!!.angle) <= 1.0E-12 && abs(mid.verticalError) < abs(best!!.verticalError)))) {
                    best = mid
                }
                val oppositeSigns = (lo.verticalError <= 0.0 && mid.verticalError >= 0.0) ||
                    (lo.verticalError >= 0.0 && mid.verticalError <= 0.0)
                if (oppositeSigns) hi = mid else lo = mid
            }
            return best?.let { forwardVerify(it.angle) }
        }

        val samples = arrayOfNulls<CandidateEvaluation>(COARSE_INTERVALS + 1)
        for (index in 0..COARSE_INTERVALS) {
            val angle = if (index == COARSE_INTERVALS) maxElevationDegrees
            else minElevationDegrees + (maxElevationDegrees - minElevationDegrees) * index / COARSE_INTERVALS.toDouble()
            samples[index] = evaluate(angle)
        }
        for (index in 0 until COARSE_INTERVALS) {
            val left = samples[index]
            val right = samples[index + 1]
            if (left == null || right == null) continue
            val signChange = (left.verticalError <= 0.0 && right.verticalError >= 0.0) ||
                (left.verticalError >= 0.0 && right.verticalError <= 0.0)
            if (signChange) {
                refine(left, right)?.let { return it }
            }
        }
        // A finite-resolution tangent can sit between sign samples.  Accept only a non-positive
        // local minimum within tolerance; a small positive apex is deliberately not returned
        // because it may conceal two roots, which the bracket pass must resolve from below.
        for (index in 1 until COARSE_INTERVALS) {
            val left = samples[index - 1]
            val center = samples[index]
            val right = samples[index + 1]
            if (left != null && center != null && right != null && center.verticalError <= 0.0 &&
                abs(center.verticalError) <= VERTICAL_TOLERANCE_BLOCKS &&
                abs(center.verticalError) <= abs(left.verticalError) &&
                abs(center.verticalError) <= abs(right.verticalError)
            ) {
                forwardVerify(center.angle)?.let { return it }
            }
        }
        val tangent = samples.asSequence().filterNotNull().firstOrNull {
            abs(it.verticalError) <= TANGENT_EPSILON_BLOCKS && acceptable(it)
        }
        if (tangent != null) return asResult(tangent)
        return if (budgetExceeded) {
            failure(PitchOnlySolveStatus.BUDGET_EXCEEDED, PitchOnlySolveDiagnostic.EVALUATION_BUDGET, evaluationCount)
        } else {
            failure(PitchOnlySolveStatus.NO_SOLUTION, PitchOnlySolveDiagnostic.RESIDUAL_EXCEEDED, evaluationCount)
        }
    }

    private fun failure(
        status: PitchOnlySolveStatus,
        diagnostic: PitchOnlySolveDiagnostic,
        evaluatedCandidates: Int = 0,
    ) = PitchOnlyFireControlResult(status = status, diagnostic = diagnostic, evaluatedCandidates = evaluatedCandidates)

    private fun finite(vector: Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()

    private fun normalizedDirectionAt(family: PitchOnlyDirectionFamily, angle: Double): Vec3? {
        if (!angle.isFinite()) return null
        val raw = runCatching { family.directionAtElevationDegrees(angle) }.getOrNull() ?: return null
        if (!finite(raw) || raw.lengthSqr() <= DIRECTION_EPSILON_SQ) return null
        val direction = raw.normalize()
        return direction.takeIf { finite(it) && it.lengthSqr() > DIRECTION_EPSILON_SQ }
    }
}
