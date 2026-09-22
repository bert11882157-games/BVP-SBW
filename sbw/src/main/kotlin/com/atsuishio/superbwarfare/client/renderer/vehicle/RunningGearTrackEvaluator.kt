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
        linkPoseInto(
            profile.path(side),
            profile.evaluationLayout,
            profile.phaseDistance,
            profile.travelScale,
            profile.linkHalfThickness,
            profile.linkFit,
            linkIndex,
            trackPhase,
            output,
            offset,
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
    ) {
        require(offset >= 0 && offset + 3 < output.size) { "Track link pose output requires four floats" }
        val halfPhase = phaseDistance * 0.5F
        val basePhase = phaseDistance * linkIndex
        val currentPhase = wrap(trackPhase + basePhase, 100.0F)

        if (linkFit == TrackLinkFit.RIGID_PATH) {
            // Geometry is centered on its authored phase-zero sample and retains its source length.
            output[offset] = sampleY(path, bounds, currentPhase) - sampleY(path, bounds, basePhase)
            output[offset + 1] = sampleZ(path, bounds, currentPhase) - sampleZ(path, bounds, basePhase)
            output[offset + 2] = sampleRotation(path, currentPhase)
            output[offset + 3] = 1F
            return
        }

        val baseStartY = sampleY(path, bounds, basePhase - halfPhase)
        val baseStartZ = sampleZ(path, bounds, basePhase - halfPhase)
        val baseEndY = sampleY(path, bounds, basePhase + halfPhase)
        val baseEndZ = sampleZ(path, bounds, basePhase + halfPhase)
        val baseDeltaY = baseEndY - baseStartY
        val baseDeltaZ = baseEndZ - baseStartZ
        val baseLength = Math.hypot(baseDeltaY.toDouble(), baseDeltaZ.toDouble()).toFloat()
        val baseRotation = chordRotationDegrees(baseDeltaY, baseDeltaZ)

        val currentStartY = sampleY(path, bounds, currentPhase - halfPhase)
        val currentStartZ = sampleZ(path, bounds, currentPhase - halfPhase)
        val currentEndY = sampleY(path, bounds, currentPhase + halfPhase)
        val currentEndZ = sampleZ(path, bounds, currentPhase + halfPhase)
        val currentDeltaY = currentEndY - currentStartY
        val currentDeltaZ = currentEndZ - currentStartZ
        val currentLength = Math.hypot(currentDeltaY.toDouble(), currentDeltaZ.toDouble()).toFloat()
        val currentRotation = chordRotationDegrees(currentDeltaY, currentDeltaZ)

        var startExtension = 0F
        var endExtension = 0F
        if (halfThickness > 0F && currentLength > 1.0E-6F) {
            val previousY = sampleY(path, bounds, currentPhase - 3F * halfPhase)
            val previousZ = sampleZ(path, bounds, currentPhase - 3F * halfPhase)
            val nextY = sampleY(path, bounds, currentPhase + 3F * halfPhase)
            val nextZ = sampleZ(path, bounds, currentPhase + 3F * halfPhase)
            val previousRotation = chordRotationDegrees(currentStartY - previousY, currentStartZ - previousZ)
            val nextRotation = chordRotationDegrees(nextY - currentEndY, nextZ - currentEndZ)
            startExtension = jointExtension(previousRotation, currentRotation, halfThickness)
            endExtension = jointExtension(currentRotation, nextRotation, halfThickness)
        }

        val baseMidY = (baseStartY + baseEndY) * 0.5F
        val baseMidZ = (baseStartZ + baseEndZ) * 0.5F
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

    private fun sampleY(path: TrackPathProfile, bounds: TrackBoundsProfile, phase: Float): Float {
        val yBottom = bounds.yCenter - bounds.radius
        val yTop = bounds.yCenter + bounds.radius
        val yScale = (yTop - yBottom) / (path.sourceYMax - path.sourceYMin)
        return yBottom + (interpolate(path.moveY, phase) - path.sourceYMin) * yScale
    }

    private fun sampleZ(path: TrackPathProfile, bounds: TrackBoundsProfile, phase: Float): Float {
        val zScale = (bounds.zFront - bounds.zRear) / (path.sourceZMax - path.sourceZMin)
        return bounds.zRear + (interpolate(path.moveZ, phase) - path.sourceZMin) * zScale
    }

    private fun sampleRotation(path: TrackPathProfile, phase: Float): Float =
        -interpolate(path.rotationX, phase)

    private fun interpolate(curve: List<TrackPathKeyframe>, phase: Float): Float {
        val wrapped = wrap(phase, 100.0F)
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
        val from = curve[low - 1]
        val to = curve[low]
        val amount = (wrapped - from.phase) / (to.phase - from.phase)
        return Mth.lerp(amount, from.value, to.value)
    }

    private fun wrap(value: Float, range: Float): Float = ((value % range) + range) % range
}
