package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import kotlin.math.*

/** Critically damped render-time chase of a world target relative to the unshifted body. */
class FixedWingCameraMotion {
    private class Axis {
        var value = 0.0
        var velocity = 0.0
        fun advance(target: Double, seconds: Double, frequency: Double) {
            val error = value - target
            val impulse = (velocity + frequency * error) * seconds
            val decay = exp(-frequency * seconds)
            value = target + (error + impulse) * decay
            velocity = (velocity - frequency * impulse) * decay
            if (abs(value - target) < 1.0e-8 && abs(velocity) < 1.0e-8) {
                value = target; velocity = 0.0
            }
        }
        fun reset() { value = 0.0; velocity = 0.0 }
    }
    private val yaw = Axis()
    private val pitch = Axis()
    private var frame = Long.MIN_VALUE
    private var nanos = 0L
    private var angles = 0.0 to 0.0
    private data class TargetSample(val nanos: Long, val yaw: Double, val pitch: Double)
    private val targets = java.util.ArrayDeque<TargetSample>()

    private fun delayedTarget(now: Long, targetYaw: Double, targetPitch: Double,
                              baseYaw: Double, basePitch: Double): TargetSample {
        if (targets.isEmpty()) {
            targets.add(TargetSample(now - CHASE_DELAY_NANOS, baseYaw + yaw.value,
                basePitch + pitch.value))
        }
        // Millisecond buckets bound storage even when rendering faster than 1,000 FPS.
        // Preserve the bucket time; the delay has at most one millisecond of quantization.
        val last = targets.last
        if (now - last.nanos < 1_000_000L) {
            targets.removeLast()
            targets.add(TargetSample(last.nanos, targetYaw, targetPitch))
        } else {
            targets.add(TargetSample(now, targetYaw, targetPitch))
        }
        val cutoff = now - CHASE_DELAY_NANOS
        while (targets.size > 1) {
            val first = targets.removeFirst()
            if (targets.first.nanos > cutoff) {
                targets.addFirst(first)
                break
            }
        }
        return targets.first
    }

    @Suppress("UNUSED_PARAMETER") // Manual axes own surfaces, never camera chase; retain the caller ABI.
    fun advance(frameId: Long, nowNanos: Long, target: FixedWingPilotIntent?,
                baseYaw: Double, basePitch: Double, strength: Double, manual: Boolean) {
        if (frame == frameId) return
        val seconds = if (frame == Long.MIN_VALUE) 0.0 else (nowNanos - nanos) * 1.0e-9
        frame = frameId; nanos = nowNanos
        if (!baseYaw.isFinite() || !basePitch.isFinite() || !strength.isFinite() || strength <= 0.0 ||
            target == null || !seconds.isFinite() || seconds < 0.0 || seconds > MAX_FRAME_GAP_SECONDS) {
            yaw.reset(); pitch.reset(); targets.clear(); angles = 0.0 to 0.0
            return
        }
        var aimYaw = Math.toDegrees(atan2(-target.directionX, target.directionZ))
        var aimPitch = Math.toDegrees(atan2(-target.directionY, hypot(target.directionX, target.directionZ)))
        // Equivalent direction branches meet at vertical. Choose the one near the camera's
        // body reference instead of making a forward pull-through look like a 180-degree yaw.
        val alternateYaw = aimYaw + 180.0
        val alternatePitch = 180.0 - aimPitch
        fun distance(y: Double, p: Double): Double =
            wrap(y - baseYaw - yaw.value).pow(2) + wrap(p - basePitch - pitch.value).pow(2)
        if (distance(alternateYaw, alternatePitch) < distance(aimYaw, aimPitch)) {
            aimYaw = alternateYaw; aimPitch = alternatePitch
        }
        val delayed = delayedTarget(nowNanos, aimYaw, aimPitch, baseYaw, basePitch)
        val targetYaw = delayed.yaw
        val targetPitch = delayed.pitch
        // Unwrap around the current chase so crossing behind the aircraft never jumps a turn.
        val desiredYaw = yaw.value + wrap(targetYaw - baseYaw - yaw.value)
        val desiredPitch = pitch.value + wrap(targetPitch - basePitch - pitch.value)
        // Strength controls response timing, not how far the pilot may look.
        val frequency = 12.0 + 24.0 * strength.coerceIn(0.0, 1.0)
        yaw.advance(desiredYaw, seconds.coerceAtMost(0.1), frequency)
        pitch.advance(desiredPitch, seconds.coerceAtMost(0.1), frequency)
        angles = yaw.value to pitch.value
    }

    /** Repeated camera position/rotation queries consume the same completed frame sample. */
    fun sample(): Pair<Double, Double> = angles

    fun reset() {
        yaw.reset(); pitch.reset(); targets.clear(); angles = 0.0 to 0.0
        frame = Long.MIN_VALUE; nanos = 0L
    }

    companion object {
        private const val CHASE_DELAY_NANOS = 75_000_000L
        private const val MAX_FRAME_GAP_SECONDS = 0.25
        private fun wrap(degrees: Double): Double = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }
}
