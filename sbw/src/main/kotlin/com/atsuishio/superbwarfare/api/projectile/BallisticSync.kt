package com.atsuishio.superbwarfare.api.projectile

import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Unguided rounds whose client copy flies the same deterministic air step as the server. The server
 * sends a step-aligned state only when the real flight leaves that prediction (impact, ricochet,
 * fluid drag, external motion) and as a sparse periodic check; clients blend small corrections.
 */
interface SmoothedBallisticProjectile {
    /** Evaluated identically on both sides from the synchronized profile snapshot. */
    fun smoothsBallisticFlight(): Boolean

    /** One deterministic air step of the velocity, exactly as this projectile's tick applies it. */
    fun ballisticStep(velocity: Vec3): Vec3

    /** Client: authoritative state after [tick] simulation steps. */
    fun acceptBallisticState(tick: Int, position: Vec3, velocity: Vec3)
}

object BallisticSync {
    /** Sparse periodic realignment; deviations are published on the step they occur. */
    const val CORRECTION_INTERVAL_TICKS = 10
    /** Server steps the client may be ahead of or behind a message; beyond this the state is applied directly. */
    const val MAX_ALIGNED_STEPS = 64
    /** A small correction is spread over this many client ticks instead of snapping. */
    const val BLEND_TICKS = 3
    private const val POSITION_TOLERANCE_SQR = 1.0E-6
    private const val VELOCITY_TOLERANCE_SQR = 1.0E-8

    /** Server: whether the finished step left the deterministic prediction the clients are running. */
    @JvmStatic
    fun deviates(startPosition: Vec3, startVelocity: Vec3, endPosition: Vec3, endVelocity: Vec3,
                 predictedVelocity: Vec3): Boolean {
        val px = startPosition.x + startVelocity.x - endPosition.x
        val py = startPosition.y + startVelocity.y - endPosition.y
        val pz = startPosition.z + startVelocity.z - endPosition.z
        if (!(px * px + py * py + pz * pz <= POSITION_TOLERANCE_SQR)) return true
        return !(predictedVelocity.distanceToSqr(endVelocity) <= VELOCITY_TOLERANCE_SQR)
    }

    /**
     * Beyond this error the client applies the authoritative position directly. Two ticks of travel on top of 4
     * blocks (owner 2026-09-30: fast munitions stuttered; 4 + v/4 was less than one tick of a missile's travel, so
     * ordinary timing jitter snapped them).
     */
    @JvmStatic
    fun snapDistance(speed: Double): Double = 4.0 + 2.0 * (if (speed.isFinite()) speed.coerceAtLeast(0.0) else 0.0)

    /**
     * An unaligned (vanilla tracker) position carries no simulation step, so any difference up to the
     * distance flown in [MAX_ALIGNED_STEPS] steps (plus drop) is timing, not error. Only a larger gap,
     * such as a command teleport, is applied from it; step-aligned messages own ordinary corrections.
     */
    @JvmStatic
    fun grossDivergence(error: Vec3, velocity: Vec3): Boolean {
        if (!finite(error)) return false
        val speed = if (finite(velocity)) velocity.length() else 0.0
        val slack = MAX_ALIGNED_STEPS * max(speed, 0.0) + 128.0
        return error.lengthSqr() > slack * slack
    }

    /**
     * Moves an authoritative state from [fromTick] to [toTick] simulation steps with the same
     * deterministic step, forwards or backwards; null when the gap is out of range or invalid.
     */
    fun align(fromTick: Int, toTick: Int, position: Vec3, velocity: Vec3,
              step: (Vec3) -> Vec3, unstep: (Vec3) -> Vec3): Pair<Vec3, Vec3>? {
        if (fromTick < 0 || toTick < 0 || !finite(position) || !finite(velocity)) return null
        val steps = toTick.toLong() - fromTick.toLong()
        if (steps > MAX_ALIGNED_STEPS || steps < -MAX_ALIGNED_STEPS) return null
        var p = position
        var v = velocity
        if (steps >= 0) {
            repeat(steps.toInt()) { p = p.add(v); v = step(v) }
        } else {
            repeat((-steps).toInt()) { v = unstep(v); p = p.subtract(v) }
        }
        return if (finite(p) && finite(v)) p to v else null
    }

    @JvmStatic
    fun finite(value: Vec3?): Boolean =
        value != null && value.x.isFinite() && value.y.isFinite() && value.z.isFinite()

    /** Client-side bounded blend of one outstanding correction. */
    class Correction {
        private var x = 0.0
        private var y = 0.0
        private var z = 0.0
        private var ticks = 0

        /** Returns false when the error is too large to blend; the caller then applies it directly. */
        fun offer(error: Vec3, speed: Double): Boolean {
            if (!finite(error)) return false
            val distance = sqrt(error.lengthSqr())
            if (distance > snapDistance(speed)) {
                clear()
                return false
            }
            // The newest aligned state supersedes any older, still-unapplied correction.
            x = error.x; y = error.y; z = error.z
            ticks = if (distance <= 1.0E-4) 0 else BLEND_TICKS
            return true
        }

        /** The share of the outstanding correction to apply on this client tick, or null. */
        fun next(): Vec3? {
            if (ticks <= 0) return null
            val fraction = 1.0 / ticks
            val step = Vec3(x * fraction, y * fraction, z * fraction)
            x -= step.x; y -= step.y; z -= step.z
            ticks--
            return step
        }

        fun clear() {
            x = 0.0; y = 0.0; z = 0.0; ticks = 0
        }

        fun pending(): Boolean = ticks > 0
    }
}
