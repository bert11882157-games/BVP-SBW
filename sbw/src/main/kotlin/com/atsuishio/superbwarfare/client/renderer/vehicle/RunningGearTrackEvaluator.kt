package com.atsuishio.superbwarfare.client.renderer.vehicle

import net.minecraft.util.Mth

/** One absolute point on a validated, vehicle-scaled track path. */
data class RunningGearTrackSample(
    val y: Float,
    val z: Float,
    val rotationXDegrees: Float,
)

/** Bone offsets for one animated link relative to its authored base phase. */
data class RunningGearTrackLinkPose(
    val moveY: Float,
    val moveZ: Float,
    val rotationXDegrees: Float,
    val longitudinalScale: Float,
)

/** Shared deterministic evaluator for Gecko bones and PolyMesh bones. */
object RunningGearTrackEvaluator {
    @JvmStatic
    fun normalizePhase(value: Float, wrapRange: Int): Float {
        if (wrapRange <= 0) return wrap(value, 100.0F)
        return wrap(value, wrapRange.toFloat()) * 100.0F / wrapRange
    }

    @JvmStatic
    fun sample(profile: TrackRenderProfile, phase: Float): RunningGearTrackSample {
        return sample(profile.path, profile.evaluationLayout, phase)
    }

    @JvmStatic
    fun sample(profile: TrackRenderProfile, side: RunningGearSide, phase: Float): RunningGearTrackSample {
        return sample(profile.path(side), profile.evaluationLayout, phase)
    }

/** Allocation-free client-render sampling for cached ordinary track transforms. */
    @JvmStatic
    fun sampleInto(profile: TrackRenderProfile, phase: Float, output: FloatArray, offset: Int) {
        require(offset >= 0 && offset + 2 < output.size) { "Track sample output requires three floats" }
        val path = profile.path
        val bounds = profile.evaluationLayout
        output[offset] = sampleY(path, bounds, phase)
        output[offset + 1] = sampleZ(path, bounds, phase)
        output[offset + 2] = sampleRotation(path, phase)
    }

    @JvmStatic
    fun sampleInto(
        profile: TrackRenderProfile,
        side: RunningGearSide,
        phase: Float,
        output: FloatArray,
        offset: Int,
    ) {
        require(offset >= 0 && offset + 2 < output.size) { "Track sample output requires three floats" }
        val path = profile.path(side)
        val bounds = profile.evaluationLayout
        output[offset] = sampleY(path, bounds, phase)
        output[offset + 1] = sampleZ(path, bounds, phase)
        output[offset + 2] = sampleRotation(path, phase)
    }

    @JvmStatic
    fun linkPose(
        profile: TrackRenderProfile,
        linkIndex: Int,
        trackPhase: Float,
    ): RunningGearTrackLinkPose {
        val output = FloatArray(4)
        linkPoseInto(profile, linkIndex, trackPhase, output, 0)
        return RunningGearTrackLinkPose(output[0], output[1], output[2], output[3])
    }

    @JvmStatic
    fun linkPose(
        profile: TrackRenderProfile,
        side: RunningGearSide,
        linkIndex: Int,
        trackPhase: Float,
    ): RunningGearTrackLinkPose {
        val output = FloatArray(4)
        linkPoseInto(profile, side, linkIndex, trackPhase, output, 0)
        return RunningGearTrackLinkPose(output[0], output[1], output[2], output[3])
    }

    /** Allocation-free bone-link evaluation for renderers with caller-owned scratch storage. */
    @JvmStatic
    fun linkPoseInto(
        profile: TrackRenderProfile,
        linkIndex: Int,
        trackPhase: Float,
        output: FloatArray,
        offset: Int,
    ) {
        linkPoseInto(
            profile.path,
            profile.evaluationLayout,
            profile.phaseDistance,
            profile.travelScale,
            profile.linkHalfThickness,
            profile.linkFit,
            linkIndex,
            trackPhase,
            output,
            offset,
            baseTable(profile, 0, profile.path),
        )
    }

    @JvmStatic
    fun linkPoseInto(
        profile: TrackRenderProfile,
        side: RunningGearSide,
        linkIndex: Int,
        trackPhase: Float,
        output: FloatArray,
        offset: Int,
    ) {
        val path = profile.path(side)
        linkPoseInto(
            path,
            profile.evaluationLayout,
            profile.phaseDistance,
            profile.travelScale,
            profile.linkHalfThickness,
            profile.linkFit,
            linkIndex,
            trackPhase,
            output,
            offset,
            baseTable(profile, if (side == RunningGearSide.LEFT) 1 else 2, path),
        )
    }

    /**
     * Evaluates shared path boundaries, then covers the outside of each bend using the authored
     * tread thickness. Centerline-only chords leave a wedge between thick links around a wheel.
     * The fourth component changes local Z span without changing width or thickness.
     */
    private fun linkPoseInto(
        path: TrackPathProfile,
        bounds: TrackBoundsProfile,
        phaseDistance: Float,
        travelScale: Float,
        halfThickness: Float,
        linkFit: TrackLinkFit,
        linkIndex: Int,
        trackPhase: Float,
        output: FloatArray,
        offset: Int,
        bases: FloatArray? = null,
    ) {
        require(offset >= 0 && offset + 3 < output.size) { "Track link pose output requires four floats" }
        val halfPhase = phaseDistance * 0.5F
        val basePhase = phaseDistance * linkIndex
        val currentPhase = wrap(trackPhase + basePhase, 100.0F)

        // The base chord depends only on the link index: read it from the profile's table when the link is in it.
        val base = if (bases != null && linkIndex >= 0 && linkIndex * BASE_STRIDE < bases.size) {
            bases
        } else {
            FloatArray(BASE_STRIDE).also { baseInto(path, bounds, phaseDistance, linkFit, linkIndex, it, 0) }
        }
        val b = if (base === bases) linkIndex * BASE_STRIDE else 0

        if (linkFit == TrackLinkFit.RIGID_PATH) {
            // Geometry is centered on its authored phase-zero sample and retains its source length.
            val current = sampleYZ(path, bounds, currentPhase)
            output[offset] = unpackY(current) - base[b]
            output[offset + 1] = unpackZ(current) - base[b + 1]
            output[offset + 2] = sampleRotation(path, currentPhase)
            output[offset + 3] = 1F
            return
        }

        val baseMidY = base[b]
        val baseMidZ = base[b + 1]
        val baseRotation = base[b + 2]
        val baseLength = base[b + 3]

        val currentStart = sampleYZ(path, bounds, currentPhase - halfPhase)
        val currentEnd = sampleYZ(path, bounds, currentPhase + halfPhase)
        val currentStartY = unpackY(currentStart)
        val currentStartZ = unpackZ(currentStart)
        val currentEndY = unpackY(currentEnd)
        val currentEndZ = unpackZ(currentEnd)
        val currentDeltaY = currentEndY - currentStartY
        val currentDeltaZ = currentEndZ - currentStartZ
        val currentLength = Math.hypot(currentDeltaY.toDouble(), currentDeltaZ.toDouble()).toFloat()
        val currentRotation = chordRotationDegrees(currentDeltaY, currentDeltaZ)

        var startExtension = 0F
        var endExtension = 0F
        if (halfThickness > 0F && currentLength > 1.0E-6F) {
            val previous = sampleYZ(path, bounds, currentPhase - 3F * halfPhase)
            val next = sampleYZ(path, bounds, currentPhase + 3F * halfPhase)
            val previousY = unpackY(previous)
            val previousZ = unpackZ(previous)
            val nextY = unpackY(next)
            val nextZ = unpackZ(next)
            val previousRotation = chordRotationDegrees(currentStartY - previousY, currentStartZ - previousZ)
            val nextRotation = chordRotationDegrees(nextY - currentEndY, nextZ - currentEndZ)
            startExtension = jointExtension(previousRotation, currentRotation, halfThickness)
            endExtension = jointExtension(currentRotation, nextRotation, halfThickness)
        }

        val centerShift = if (currentLength > 1.0E-6F) (endExtension - startExtension) / (2F * currentLength) else 0F
        val currentMidY = (currentStartY + currentEndY) * 0.5F + currentDeltaY * centerShift
        val currentMidZ = (currentStartZ + currentEndZ) * 0.5F + currentDeltaZ * centerShift
        output[offset] = (currentMidY - baseMidY) * travelScale
        output[offset + 1] = (currentMidZ - baseMidZ) * travelScale
        output[offset + 2] = baseRotation + Mth.wrapDegrees(currentRotation - baseRotation) * travelScale
        val fittedScale = if (baseLength > 1.0E-6F)
            (currentLength + startExtension + endExtension) / baseLength else 1.0F
        output[offset + 3] = Mth.lerp(travelScale, 1.0F, fittedScale)
    }

    private const val BASE_STRIDE = 4

    /** The profile's base table for one path, built for all of its links on first use. */
    private fun baseTable(profile: TrackRenderProfile, slot: Int, path: TrackPathProfile): FloatArray {
        profile.linkBases[slot]?.let { return it }
        val count = profile.linkCount.coerceIn(0, 4096)
        val table = FloatArray(count * BASE_STRIDE)
        for (index in 0 until count) {
            baseInto(path, profile.evaluationLayout, profile.phaseDistance, profile.linkFit, index, table, index * BASE_STRIDE)
        }
        profile.linkBases[slot] = table
        return table
    }

    /**
     * The link's authored base: for a rigid link its phase-zero point (Y, Z); otherwise the midpoint (Y, Z),
     * rotation and length of its contact chord.
     */
    private fun baseInto(path: TrackPathProfile, bounds: TrackBoundsProfile, phaseDistance: Float, linkFit: TrackLinkFit,
                         linkIndex: Int, out: FloatArray, o: Int) {
        val halfPhase = phaseDistance * 0.5F
        val basePhase = phaseDistance * linkIndex
        if (linkFit == TrackLinkFit.RIGID_PATH) {
            val point = sampleYZ(path, bounds, basePhase)
            out[o] = unpackY(point)
            out[o + 1] = unpackZ(point)
            return
        }
        val start = sampleYZ(path, bounds, basePhase - halfPhase)
        val end = sampleYZ(path, bounds, basePhase + halfPhase)
        val baseStartY = unpackY(start)
        val baseStartZ = unpackZ(start)
        val baseEndY = unpackY(end)
        val baseEndZ = unpackZ(end)
        val baseDeltaY = baseEndY - baseStartY
        val baseDeltaZ = baseEndZ - baseStartZ
        out[o] = (baseStartY + baseEndY) * 0.5F
        out[o + 1] = (baseStartZ + baseEndZ) * 0.5F
        out[o + 2] = chordRotationDegrees(baseDeltaY, baseDeltaZ)
        out[o + 3] = Math.hypot(baseDeltaY.toDouble(), baseDeltaZ.toDouble()).toFloat()
    }

    /** Y and Z of one path point packed into a Long (allocation-free); one keyframe search when they share phases. */
    private fun sampleYZ(path: TrackPathProfile, bounds: TrackBoundsProfile, phase: Float): Long {
        if (!path.sharedYZPhases) return pack(sampleY(path, bounds, phase), sampleZ(path, bounds, phase))
        val wrapped = wrap(phase, 100.0F)
        val low = segment(path.moveY, wrapped)
        return pack(scaleY(path, bounds, lerpAt(path.moveY, low, wrapped)),
            scaleZ(path, bounds, lerpAt(path.moveZ, low, wrapped)))
    }

    private fun pack(y: Float, z: Float): Long =
        (y.toRawBits().toLong() shl 32) or (z.toRawBits().toLong() and 0xFFFFFFFFL)
    private fun unpackY(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())
    private fun unpackZ(packed: Long): Float = Float.fromBits(packed.toInt())

    private fun jointExtension(fromDegrees: Float, toDegrees: Float, halfThickness: Float): Float {
        val halfAngle = Math.toRadians(kotlin.math.abs(Mth.wrapDegrees(toDegrees - fromDegrees)).toDouble() * 0.5)
        // A cusp cannot have a finite miter. Bound pathological profiles while preserving ordinary wheel bends.
        return (halfThickness * kotlin.math.tan(halfAngle).coerceAtMost(4.0)).toFloat()
    }

    private fun chordRotationDegrees(deltaY: Float, deltaZ: Float): Float =
        Math.toDegrees(Math.atan2(-deltaY.toDouble(), deltaZ.toDouble())).toFloat()

    private fun sample(
        path: TrackPathProfile,
        bounds: TrackBoundsProfile,
        phase: Float,
    ): RunningGearTrackSample {
        return RunningGearTrackSample(
            sampleY(path, bounds, phase),
            sampleZ(path, bounds, phase),
            sampleRotation(path, phase),
        )
    }

    private fun sampleY(path: TrackPathProfile, bounds: TrackBoundsProfile, phase: Float): Float =
        scaleY(path, bounds, interpolate(path.moveY, phase))

    private fun sampleZ(path: TrackPathProfile, bounds: TrackBoundsProfile, phase: Float): Float =
        scaleZ(path, bounds, interpolate(path.moveZ, phase))

    private fun scaleY(path: TrackPathProfile, bounds: TrackBoundsProfile, value: Float): Float {
        val yBottom = bounds.yCenter - bounds.radius
        val yTop = bounds.yCenter + bounds.radius
        val yScale = (yTop - yBottom) / (path.sourceYMax - path.sourceYMin)
        return yBottom + (value - path.sourceYMin) * yScale
    }

    private fun scaleZ(path: TrackPathProfile, bounds: TrackBoundsProfile, value: Float): Float {
        val zScale = (bounds.zFront - bounds.zRear) / (path.sourceZMax - path.sourceZMin)
        return bounds.zRear + (value - path.sourceZMin) * zScale
    }

    private fun sampleRotation(path: TrackPathProfile, phase: Float): Float =
        -interpolate(path.rotationX, phase)

    private fun interpolate(curve: List<TrackPathKeyframe>, phase: Float): Float {
        val wrapped = wrap(phase, 100.0F)
        return lerpAt(curve, segment(curve, wrapped), wrapped)
    }

    /**
     * Index of the first keyframe at or after the wrapped phase (at least 1; the last when none is). Keyframes are
     * sorted and, in generated profiles, evenly spaced: start from the proportional guess and step to the exact
     * index, which gives the binary search's answer in one or two probes instead of seven.
     */
    internal fun segment(curve: List<TrackPathKeyframe>, wrapped: Float): Int {
        val last = curve.lastIndex
        if (last < 1 || wrapped.isNaN()) return binarySegment(curve, wrapped)
        val first = curve[0].phase
        val span = curve[last].phase - first
        if (!(span > 0F)) return binarySegment(curve, wrapped)
        var index = kotlin.math.ceil((wrapped - first) / span * last).toInt().coerceIn(1, last)
        while (index > 1 && wrapped <= curve[index - 1].phase) index--
        while (index < last && wrapped > curve[index].phase) index++
        return index
    }

    internal fun binarySegment(curve: List<TrackPathKeyframe>, wrapped: Float): Int {
        var low = 1
        var high = curve.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (wrapped <= curve[middle].phase) {
                high = middle
            } else {
                low = middle + 1
            }
        }
        return low
    }

    private fun lerpAt(curve: List<TrackPathKeyframe>, low: Int, wrapped: Float): Float {
        val from = curve[low - 1]
        val to = curve[low]
        val amount = (wrapped - from.phase) / (to.phase - from.phase)
        return Mth.lerp(amount, from.value, to.value)
    }

    private fun wrap(value: Float, range: Float): Float = ((value % range) + range) % range
}
