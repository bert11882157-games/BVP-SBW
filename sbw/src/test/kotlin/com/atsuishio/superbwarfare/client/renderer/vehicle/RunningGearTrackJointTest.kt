package com.atsuishio.superbwarfare.client.renderer.vehicle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.*

class RunningGearTrackJointTest {
    private fun circle(thickness: Float, reverse: Boolean = false): TrackRenderProfile {
        val points = (0..128).map { index ->
            val angle = 2.0 * PI * index / 128 * if (reverse) -1 else 1
            TrackPathKeyframe(index * 100F / 128, (3.0 * sin(angle)).toFloat()) to
                TrackPathKeyframe(index * 100F / 128, (3.0 * cos(angle)).toFloat())
        }
        val path = TrackPathProfile(-3F, 3F, -3F, 3F,
            points.map { it.first }, points.map { it.second },
            listOf(TrackPathKeyframe(0F, 0F), TrackPathKeyframe(100F, 360F)))
        return TrackRenderProfile(TrackRenderMode.LINKS, 12, 100F / 12, 1F,
            TrackBoundsProfile(0F, 3F, -3F, 3F), emptyMap(), path, thickness,
            TrackLinkFit.CONTACT_INTERVAL)
    }

    private data class Link(val y: Double, val z: Double, val angle: Double, val halfLength: Double) {
        fun corner(end: Boolean, height: Double): Pair<Double, Double> {
            val along = if (end) halfLength else -halfLength
            return y + cos(angle) * height - sin(angle) * along to
                z + sin(angle) * height + cos(angle) * along
        }
    }

    private fun links(profile: TrackRenderProfile, phase: Float): List<Link> =
        (0 until profile.linkCount).map { index ->
            val basePhase = index * profile.phaseDistance
            val a = RunningGearTrackEvaluator.sample(profile, basePhase - profile.phaseDistance * 0.5F)
            val b = RunningGearTrackEvaluator.sample(profile, basePhase + profile.phaseDistance * 0.5F)
            val pose = RunningGearTrackEvaluator.linkPose(profile, index, phase)
            Link((a.y + b.y) * 0.5 + pose.moveY, (a.z + b.z) * 0.5 + pose.moveZ,
                Math.toRadians(pose.rotationXDegrees.toDouble()),
                hypot((b.y - a.y).toDouble(), (b.z - a.z).toDouble()) * pose.longitudinalScale * 0.5)
        }

    private fun outerGap(profile: TrackRenderProfile, phase: Float, height: Double): Double {
        val links = links(profile, phase)
        return links.indices.maxOf { index ->
            val first = links[index]
            val second = links[(index + 1) % links.size]
            val turn = atan2(sin(second.angle - first.angle), cos(second.angle - first.angle))
            val edge = height * sign(turn)
            val a = first.corner(true, edge)
            val b = second.corner(false, edge)
            hypot(a.first - b.first, a.second - b.second)
        }
    }

    @Test fun `thick links close outer tread corners in both wheel directions`() {
        for (reverse in listOf(false, true)) {
            val old = circle(0F, reverse)
            assertTrue(outerGap(old, 0F, 0.3) > 0.15, "Centerline fit must reproduce the outer tread gap")
            val fitted = circle(0.3F, reverse)
            for (phase in listOf(0F, 0.17F, 3.1F, 17.9F, 99.99F)) {
                assertTrue(outerGap(fitted, phase, 0.3) < 1E-4,
                    "Outer tread joint must close at phase $phase, reverse=$reverse")
            }
        }
    }

    @Test fun `straight links retain original span and center`() {
        val path = TrackPathProfile(0F, 2F, -10F, 10F,
            listOf(TrackPathKeyframe(0F, 0F), TrackPathKeyframe(45F, 0F),
                TrackPathKeyframe(50F, 2F), TrackPathKeyframe(95F, 2F), TrackPathKeyframe(100F, 0F)),
            listOf(TrackPathKeyframe(0F, -10F), TrackPathKeyframe(45F, 10F),
                TrackPathKeyframe(50F, 10F), TrackPathKeyframe(95F, -10F), TrackPathKeyframe(100F, -10F)),
            listOf(TrackPathKeyframe(0F, 0F), TrackPathKeyframe(100F, 360F)))
        val base = TrackRenderProfile(TrackRenderMode.LINKS, 40, 2.5F, 1F,
            TrackBoundsProfile(1F, 1F, -10F, 10F), emptyMap(), path)
        val fitted = base.copy(linkHalfThickness = 0.3F)
        for (index in 3..12) {
            assertEquals(RunningGearTrackEvaluator.linkPose(base, index, 0.3F),
                RunningGearTrackEvaluator.linkPose(fitted, index, 0.3F))
        }
    }

    @Test fun `legacy profiles retain centerline closure and zero extra scale at rest`() {
        val profile = circle(0F)
        assertTrue(outerGap(profile, 3F, 0.0) < 1E-4)
        for (index in 0 until profile.linkCount) {
            val pose = RunningGearTrackEvaluator.linkPose(profile, index, 0F)
            assertEquals(0F, pose.moveY, 1E-5F)
            assertEquals(0F, pose.moveZ, 1E-5F)
            assertEquals(1F, pose.longitudinalScale, 1E-5F)
        }
    }
}
