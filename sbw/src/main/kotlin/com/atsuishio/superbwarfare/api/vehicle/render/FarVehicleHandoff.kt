package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Render-only continuity between independently buffered tracking and far-frame streams. */
class FarVehicleHandoff {
    /** Both streams observed while the tracked entity still exists, before exchanging renderers. */
    data class ClockOverlap(
        val farAnchor: Vec3,
        val farYaw: Float,
        val farAuthority: Vec3,
        val trackedAuthority: Vec3,
    )

    private data class State(
        val identity: String,
        val copy: Boolean,
        val tick: Double,
        val anchor: Vec3,
        val yaw: Float,
        val velocity: Vec3,
        val offset: Vec3 = Vec3.ZERO,
        val yawOffset: Float = 0F,
        val started: Double = Double.NEGATIVE_INFINITY,
        val overlap: ClockOverlap? = null,
    )

    private val states = LinkedHashMap<UUID, State>(16, 0.75F, true)
    internal val size: Int get() = states.size
    fun clear() = states.clear()
    fun forget(id: UUID) { states.remove(id) }

    fun sample(id: UUID, identity: String, copy: Boolean, tick: Double,
               input: VehicleChassisPresentation,
               overlap: ClockOverlap? = null): VehicleChassisPresentation {
        if (!tick.isFinite() || !finite(input.anchor) || !input.chassisYawDegrees.isFinite()) {
            forget(id)
            return input
        }
        val old = states[id]?.takeIf {
            it.identity == identity && tick >= it.tick && tick - it.tick <= 2.0 &&
                input.mode != VehicleChassisPresentation.Mode.DISCONTINUITY
        }
        var offset = old?.offset ?: Vec3.ZERO
        var yawOffset = old?.yawOffset ?: 0F
        var started = old?.started ?: Double.NEGATIVE_INFINITY
        if (old != null && old.copy != copy) {
            // Continue the last displayed motion at the exchange, then retire the presentation
            // correction. Large teleports remain discontinuities instead of dragging the model.
            val expected = old.anchor.add(old.velocity.scale((tick - old.tick).coerceAtMost(1.0)))
            val correction = expected.subtract(input.anchor)
            val angle = Mth.wrapDegrees(old.yaw - input.chassisYawDegrees)
            // Legacy ground tracking can trail its latest packet target by ten ticks. Compare
            // the incoming far stream with its observed overlap, not that unrelated near clock.
            // The eight-block teleport bound still applies to new, unexplained displacement.
            val incoming = if (copy) old.overlap else null
            val incomingExpected = incoming?.farAnchor?.add(
                old.velocity.scale((tick - old.tick).coerceAtMost(1.0)))
            val bounded = correction.lengthSqr() <= MAX_CORRECTION_SQUARED ||
                (incoming != null && incomingExpected != null &&
                    incomingExpected.distanceToSqr(input.anchor) <= MAX_CORRECTION_SQUARED &&
                    explainedByLegacyClock(correction, old.velocity) &&
                    kotlin.math.abs(Mth.wrapDegrees(incoming.farYaw - input.chassisYawDegrees)) <= 45F)
            if (bounded && kotlin.math.abs(angle) <= 45F) {
                offset = correction
                yawOffset = angle
                started = tick
            } else {
                offset = Vec3.ZERO
                yawOffset = 0F
            }
        }
        val progress = ((tick - started) / BLEND_TICKS).coerceIn(0.0, 1.0)
        val weight = 1.0 - progress * progress * (3.0 - 2.0 * progress)
        val anchor = input.anchor.add(offset.scale(weight))
        val yaw = input.chassisYawDegrees + yawOffset * weight.toFloat()
        val dt = if (old == null) 0.0 else tick - old.tick
        val velocity = if (old != null && dt > 1.0E-6) anchor.subtract(old.anchor).scale(1.0 / dt)
            .let { if (it.lengthSqr() <= 64.0) it else Vec3.ZERO } else old?.velocity ?: Vec3.ZERO
        val admittedOverlap = overlap?.takeIf {
            !copy && input.mode == VehicleChassisPresentation.Mode.LEGACY &&
                finite(it.farAnchor) && it.farYaw.isFinite() &&
                finite(it.farAuthority) && finite(it.trackedAuthority) &&
                it.farAuthority.distanceToSqr(it.trackedAuthority) <= MAX_CORRECTION_SQUARED
        }
        states[id] = State(identity, copy, tick, anchor, yaw, velocity, offset, yawOffset, started,
            admittedOverlap)
        while (states.size > FarVehicleStore.MAX_VEHICLES) states.remove(states.keys.first())
        return input.copy(anchor = anchor, chassisYawDegrees = yaw,
            pose = input.pose.copy(anchor = anchor, chassisYawDegrees = yaw))
    }

    private fun explainedByLegacyClock(correction: Vec3, velocity: Vec3): Boolean {
        val speedSquared = velocity.lengthSqr()
        val phaseTicks = if (speedSquared > 1.0E-6) correction.dot(velocity) / speedSquared else 0.0
        val measuredPhase = velocity.scale(phaseTicks.coerceIn(-LEGACY_POSITION_TICKS, LEGACY_POSITION_TICKS))
        // A matching packet target alone is insufficient: only observed motion within the existing
        // interpolation window explains a large clock offset. Stationary jumps keep the old bound.
        return correction.subtract(measuredPhase).lengthSqr() <= MAX_CORRECTION_SQUARED
    }

    private fun finite(point: Vec3) = point.x.isFinite() && point.y.isFinite() && point.z.isFinite()
    companion object {
        const val BLEND_TICKS = 6.0
        private const val MAX_CORRECTION_SQUARED = 64.0
        private const val LEGACY_POSITION_TICKS = 10.0
    }
}
