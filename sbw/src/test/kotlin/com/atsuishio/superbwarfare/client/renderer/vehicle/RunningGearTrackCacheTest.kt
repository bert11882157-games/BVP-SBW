package com.atsuishio.superbwarfare.client.renderer.vehicle

import net.minecraft.util.Mth
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

/**
 * The link-pose evaluator caches each link's phase-independent base chord on the profile and samples Y and Z with
 * one keyframe search when they share phases. Both must give exactly the poses of the direct per-sample
 * evaluation, which is reproduced here from the public single-point sampler.
 */
class RunningGearTrackCacheTest {
    private fun profile(thickness: Float, fit: TrackLinkFit, sharedPhases: Boolean): TrackRenderProfile {
        val count = 96
        val y = (0..count).map { TrackPathKeyframe(it * 100F / count, (2.0 * sin(2 * PI * it / count) + 0.3 * sin(6 * PI * it / count)).toFloat()) }
        // Unshared: Z keyed at its own phases, so the evaluator must fall back to two searches.
        val zCount = if (sharedPhases) count else count - 7
        val z = (0..zCount).map { TrackPathKeyframe(it * 100F / zCount, (4.0 * cos(2 * PI * it / zCount)).toFloat()) }
        val path = TrackPathProfile(-2.3F, 2.3F, -4F, 4F, y, z,
            listOf(TrackPathKeyframe(0F, 0F), TrackPathKeyframe(37F, 140F), TrackPathKeyframe(100F, 360F)))
        return TrackRenderProfile(TrackRenderMode.LINKS, 40, 100F / 40, 0.85F,
            TrackBoundsProfile(0.2F, 1.1F, -3.5F, 3.2F), emptyMap(), path, thickness, fit)
    }

    private fun joint(from: Float, to: Float, half: Float): Float =
        (half * tan(Math.toRadians(abs(Mth.wrapDegrees(to - from)).toDouble() * 0.5)).coerceAtMost(4.0)).toFloat()

    private fun chord(dy: Float, dz: Float): Float = Math.toDegrees(atan2(-dy.toDouble(), dz.toDouble())).toFloat()

    private fun wrap(value: Float) = ((value % 100F) + 100F) % 100F

    /** The evaluator's formula over individually sampled points. */
    private fun reference(p: TrackRenderProfile, link: Int, trackPhase: Float): FloatArray {
        fun s(phase: Float) = RunningGearTrackEvaluator.sample(p, phase)
        val half = p.phaseDistance * 0.5F
        val basePhase = p.phaseDistance * link
        val current = wrap(trackPhase + basePhase)
        if (p.linkFit == TrackLinkFit.RIGID_PATH) {
            val c = s(current); val b = s(basePhase)
            return floatArrayOf(c.y - b.y, c.z - b.z, c.rotationXDegrees, 1F)
        }
        val bs = s(basePhase - half); val be = s(basePhase + half)
        val bdy = be.y - bs.y; val bdz = be.z - bs.z
        val baseLength = Math.hypot(bdy.toDouble(), bdz.toDouble()).toFloat()
        val baseRotation = chord(bdy, bdz)
        val cs = s(current - half); val ce = s(current + half)
        val cdy = ce.y - cs.y; val cdz = ce.z - cs.z
        val length = Math.hypot(cdy.toDouble(), cdz.toDouble()).toFloat()
        val rotation = chord(cdy, cdz)
        var startExtension = 0F; var endExtension = 0F
        if (p.linkHalfThickness > 0F && length > 1.0E-6F) {
            val prev = s(current - 3F * half); val next = s(current + 3F * half)
            startExtension = joint(chord(cs.y - prev.y, cs.z - prev.z), rotation, p.linkHalfThickness)
            endExtension = joint(rotation, chord(next.y - ce.y, next.z - ce.z), p.linkHalfThickness)
        }
        val shift = if (length > 1.0E-6F) (endExtension - startExtension) / (2F * length) else 0F
        val scale = p.travelScale
        val fitted = if (baseLength > 1.0E-6F) (length + startExtension + endExtension) / baseLength else 1F
        return floatArrayOf(
            ((cs.y + ce.y) * 0.5F + cdy * shift - (bs.y + be.y) * 0.5F) * scale,
            ((cs.z + ce.z) * 0.5F + cdz * shift - (bs.z + be.z) * 0.5F) * scale,
            baseRotation + Mth.wrapDegrees(rotation - baseRotation) * scale,
            Mth.lerp(scale, 1F, fitted))
    }

    @Test fun `cached bases and shared keyframe search give the direct poses exactly`() {
        for (fit in TrackLinkFit.values()) for (shared in listOf(true, false)) for (thickness in listOf(0F, 0.35F)) {
            val p = profile(thickness, fit, shared)
            val out = FloatArray(4)
            // Twice over: the first pass builds the base table, the second reads it. Links past the table too.
            repeat(2) {
                for (link in listOf(0, 1, 7, 19, 39, 40, 45)) for (phase in listOf(0F, 0.013F, 12.5F, 49.99F, 73.2F, 99.999F)) {
                    RunningGearTrackEvaluator.linkPoseInto(p, link, phase, out, 0)
                    val expected = reference(p, link, phase)
                    for (i in 0 until 4) assertEquals(expected[i], out[i],
                        "fit=$fit shared=$shared thickness=$thickness link=$link phase=$phase component=$i")
                }
            }
        }
    }
}

class RunningGearTrackSegmentTest {
    @Test fun `proportional keyframe search finds the binary search's segment`() {
        val random = java.util.Random(7)
        val curves = mutableListOf<List<TrackPathKeyframe>>()
        curves += (0..120).map { TrackPathKeyframe(it * 100F / 120, 0F) }                 // evenly spaced
        curves += listOf(TrackPathKeyframe(0F, 0F), TrackPathKeyframe(100F, 1F))         // two keys
        curves += (0..40).map { TrackPathKeyframe((it * it) * 100F / 1600, 0F) }         // bunched at the start
        curves += (0..30).map { TrackPathKeyframe(if (it < 10) 0F else if (it > 25) 100F else it * 3.3F, 0F) } // repeats
        repeat(20) {
            var phase = 0F
            curves += (0..random.nextInt(60) + 1).map { TrackPathKeyframe(phase.also { phase += random.nextFloat() * 5F }, 0F) }
        }
        val probes = listOf(-1F, 0F, 1E-7F, 0.5F, 33.3F, 49.99F, 50F, 99.999F, 100F, 101F, Float.NaN) +
            (0 until 400).map { random.nextFloat() * 110F - 5F }
        for (curve in curves) for (phase in probes + curve.map { it.phase }) {
            assertEquals(RunningGearTrackEvaluator.binarySegment(curve, phase), RunningGearTrackEvaluator.segment(curve, phase),
                "phase $phase over ${curve.size} keys")
        }
    }
}
