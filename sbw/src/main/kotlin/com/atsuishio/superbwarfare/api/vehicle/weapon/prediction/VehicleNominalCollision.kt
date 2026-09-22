package com.atsuishio.superbwarfare.api.vehicle.weapon.prediction

import com.atsuishio.superbwarfare.entity.OBBEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * Read-only, bounded broadphase for passive CCIP vehicle impacts.
 *
 * A prediction owns exactly one candidate capture for its complete nominal path. Every segment
 * then reuses that immutable list and performs only the exact live AABB/OBB clips. The candidate
 * capture is scoped to one cursor and is never cached across ticks, levels, or snapshots.
 */
internal object VehicleNominalCollision {
    private const val MAX_CANDIDATES = 64
    private const val SWEEP_INFLATE = 1.0

    internal data class Hit(
        val point: Vec3,
        val fraction: Double,
        val entityId: Int,
    )

    internal data class Candidates(
        val source: VehicleEntity,
        val vehicles: List<VehicleEntity>,
    )

    internal data class Capture(
        val candidates: Candidates?,
        val contextUnavailable: Boolean = false,
        val deadlineExceeded: Boolean = false,
    )

    internal data class Query(
        val hit: Hit? = null,
        val contextUnavailable: Boolean = false,
    )

    /** Candidate list captured once for one full nominal path. */
    internal data class Cursor(
        val start: Vec3,
        val end: Vec3,
        val source: VehicleEntity,
        val candidates: List<VehicleEntity>,
        val index: Int = 0,
        val nearest: Hit? = null,
    )

    internal data class Begin(
        val cursor: Cursor?,
        val contextUnavailable: Boolean = false,
        val deadlineExceeded: Boolean = false,
    )

    internal data class Advance(
        val cursor: Cursor?,
        val result: Query?,
        val visited: Int,
    )

    /**
     * Captures one broadphase candidate set around every point in the bounded nominal path.
     * The caller supplies the translation-independent trajectory arrays, so this method does
     * not integrate motion or perform any per-segment world query.
     */
    fun capturePath(
        level: Level,
        source: VehicleEntity?,
        origin: Vec3,
        positionX: DoubleArray,
        positionY: DoubleArray,
        positionZ: DoubleArray,
        cumulativeTravel: DoubleArray,
        stepCount: Int,
        supportedRangeBlocks: Double,
        deadlineNanos: Long = 0L,
    ): Capture {
        if (source == null || source.isRemoved || source.level() !== level ||
            !finite(source.position()) || !finite(origin) ||
            stepCount < 0 || stepCount >= positionX.size ||
            stepCount >= positionY.size || stepCount >= positionZ.size ||
            stepCount > cumulativeTravel.size || !supportedRangeBlocks.isFinite() ||
            supportedRangeBlocks <= 0.0
        ) return Capture(null, contextUnavailable = true)
        if (deadlineReached(deadlineNanos)) return Capture(null, deadlineExceeded = true)

        val limit = min(stepCount, cumulativeTravel.size)
        var minX = origin.x
        var minY = origin.y
        var minZ = origin.z
        var maxX = origin.x
        var maxY = origin.y
        var maxZ = origin.z
        var pointIndex = 0
        while (pointIndex <= limit) {
            if (pointIndex > 0 && cumulativeTravel[pointIndex - 1] > supportedRangeBlocks) break
            val x = origin.x + positionX[pointIndex]
            val y = origin.y + positionY[pointIndex]
            val z = origin.z + positionZ[pointIndex]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) {
                return Capture(null, contextUnavailable = true)
            }
            minX = minOf(minX, x)
            minY = minOf(minY, y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
            maxZ = maxOf(maxZ, z)
            pointIndex++
        }

        val sweep = AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(SWEEP_INFLATE)
        return captureCandidates(level, source, sweep, deadlineNanos)
    }

    /** Starts the cheap exact clip for one trajectory segment using the path capture. */
    fun beginSegment(
        level: Level,
        candidates: Candidates?,
        start: Vec3,
        end: Vec3,
        deadlineNanos: Long = 0L,
    ): Begin {
        if (candidates == null || candidates.source.isRemoved || candidates.source.level() !== level ||
            !finite(start) || !finite(end)
        ) return Begin(null, contextUnavailable = true)
        if (deadlineReached(deadlineNanos)) return Begin(null, deadlineExceeded = true)
        return Begin(
            Cursor(start, end, candidates.source, candidates.vehicles),
            deadlineExceeded = deadlineReached(deadlineNanos),
        )
    }

    /** Compatibility helper for focused/unit callers that need one-segment semantics. */
    fun begin(
        level: Level,
        source: VehicleEntity?,
        start: Vec3,
        end: Vec3,
        deadlineNanos: Long = 0L,
    ): Begin {
        val captured = captureCandidates(
            level,
            source,
            AABB(
                minOf(start.x, end.x), minOf(start.y, end.y), minOf(start.z, end.z),
                maxOf(start.x, end.x), maxOf(start.y, end.y), maxOf(start.z, end.z),
            ).inflate(SWEEP_INFLATE),
            deadlineNanos,
        )
        if (captured.contextUnavailable) return Begin(null, contextUnavailable = true)
        return beginSegment(level, captured.candidates, start, end, deadlineNanos).copy(
            deadlineExceeded = captured.deadlineExceeded || deadlineReached(deadlineNanos),
        )
    }

    fun advance(level: Level, initial: Cursor, deadlineNanos: Long = 0L): Advance {
        var cursor = initial
        var visited = 0
        while (cursor.index < cursor.candidates.size) {
            if (deadlineReached(deadlineNanos)) return Advance(cursor, null, visited)
            val candidate = cursor.candidates[cursor.index]
            if (candidate.isRemoved || candidate.level() !== level) {
                cursor = cursor.copy(index = cursor.index + 1)
                visited++
                continue
            }
            if (!candidate.isAlive || !candidate.isPickable ||
                candidate === cursor.source || candidate.rootVehicle === cursor.source.rootVehicle
            ) {
                cursor = cursor.copy(index = cursor.index + 1)
                visited++
                continue
            }
            val clipped = clip(candidate, cursor.start, cursor.end, deadlineNanos)
            if (clipped.deadlineExceeded) return Advance(cursor, null, visited)
            val point = clipped.point
            var nearest = cursor.nearest
            if (point != null) {
                if (!finite(point)) return Advance(null, Query(contextUnavailable = true), visited)
                val segmentLength = cursor.start.distanceTo(cursor.end)
                val fraction = if (segmentLength <= 1.0E-9) 0.0
                else cursor.start.distanceTo(point) / segmentLength
                if (!fraction.isFinite() || fraction < -1.0E-9 || fraction > 1.0 + 1.0E-9) {
                    return Advance(null, Query(contextUnavailable = true), visited)
                }
                val boundedFraction = fraction.coerceIn(0.0, 1.0)
                val current = Hit(point, boundedFraction, candidate.id)
                val previous = nearest
                if (previous == null || boundedFraction < previous.fraction - 1.0E-9 ||
                    (kotlin.math.abs(boundedFraction - previous.fraction) <= 1.0E-9 &&
                        candidate.id < previous.entityId)
                ) nearest = current
            }
            cursor = cursor.copy(index = cursor.index + 1, nearest = nearest)
            visited++
            if (deadlineReached(deadlineNanos)) return Advance(cursor, null, visited)
        }
        return Advance(null, Query(hit = cursor.nearest), visited)
    }

    fun query(level: Level, source: VehicleEntity?, start: Vec3, end: Vec3): Query {
        val started = begin(level, source, start, end)
        if (started.contextUnavailable || started.cursor == null) {
            return Query(contextUnavailable = true)
        }
        var cursor = requireNotNull(started.cursor)
        while (true) {
            val advanced = advance(level, cursor)
            advanced.result?.let { return it }
            cursor = requireNotNull(advanced.cursor)
        }
    }

    private fun captureCandidates(
        level: Level,
        source: VehicleEntity?,
        sweep: AABB,
        deadlineNanos: Long,
    ): Capture {
        if (source == null || source.isRemoved || source.level() !== level || !finite(source.position())) {
            return Capture(null, contextUnavailable = true)
        }
        if (deadlineReached(deadlineNanos)) return Capture(null, deadlineExceeded = true)
        val sourceRoot = source.rootVehicle
        var eligibleCount = 0
        var overCandidateCap = false
        val candidates = level.getEntitiesOfClass(VehicleEntity::class.java, sweep) { candidate ->
            val eligible = candidate !== source && !candidate.isRemoved && candidate.isAlive &&
                candidate.isPickable && candidate.rootVehicle !== sourceRoot
            if (!eligible) return@getEntitiesOfClass false
            eligibleCount++
            if (eligibleCount > MAX_CANDIDATES) {
                overCandidateCap = true
                false
            } else true
        }
        if (overCandidateCap || candidates.size > MAX_CANDIDATES) {
            return Capture(null, contextUnavailable = true)
        }
        return Capture(
            Candidates(source, candidates.toList()),
            deadlineExceeded = deadlineReached(deadlineNanos),
        )
    }

    private data class ClipResult(
        val point: Vec3?,
        val deadlineExceeded: Boolean = false,
    )

    private fun clip(
        vehicle: VehicleEntity,
        start: Vec3,
        end: Vec3,
        deadlineNanos: Long,
    ): ClipResult {
        if (vehicle is OBBEntity && !vehicle.enableAABB()) {
            var nearest: Vec3? = null
            var nearestDistance = Double.POSITIVE_INFINITY
            for (obb in vehicle.getOBBs()) {
                if (deadlineReached(deadlineNanos)) return ClipResult(null, deadlineExceeded = true)
                val hit = obb.clip(OBB.vec3ToVector3d(start), OBB.vec3ToVector3d(end))
                    .orElse(null)
                    ?.let { OBB.vector3dToVec3(it) }
                    ?: continue
                val distance = start.distanceToSqr(hit)
                if (distance < nearestDistance) {
                    nearest = hit
                    nearestDistance = distance
                }
            }
            return ClipResult(nearest)
        }
        return ClipResult(vehicle.boundingBox.clip(start, end).orElse(null))
    }

    private fun deadlineReached(deadlineNanos: Long): Boolean =
        deadlineNanos != 0L && System.nanoTime() >= deadlineNanos

    private fun finite(vector: Vec3): Boolean =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
