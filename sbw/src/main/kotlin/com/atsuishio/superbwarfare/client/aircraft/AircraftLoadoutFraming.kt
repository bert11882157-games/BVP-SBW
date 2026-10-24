package com.atsuishio.superbwarfare.client.aircraft

import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** Fits the equipment view to its usable viewport rather than a padded hull sphere. */
internal object AircraftLoadoutFraming {
    /** Downward view pitch in degrees; the highest view the low ground-level framing may use. */
    const val PITCH = 12f
    /** Steepest upward view pitch in degrees for the ground-level framing. */
    const val MIN_PITCH = -35f
    /** World-space eye height above the aircraft's ground plane, in blocks. */
    const val EYE_CLEARANCE_BLOCKS = .5
    private const val PITCH_SEARCH_STEPS = 24
    /** Pilot-orbited view pitch limits in degrees (positive looks down), short of the poles. */
    const val ORBIT_MIN_PITCH = -60f
    const val ORBIT_MAX_PITCH = 85f

    /** [pitch] and [yaw] are in degrees and are the view rotation that produced [position]. */
    data class Fit(val position: Vec3, val distance: Double, val pitch: Float = PITCH, val yaw: Float = 0f)

    fun fit(center: Vec3, points: List<Vec3>, yaw: Float, width: Int, height: Int,
            verticalFov: Double, bayRows: Int, pitch: Float = PITCH): Fit {
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val forward = Vec3.directionFromRotation(pitch, yaw).normalize()
        val right = forward.cross(Vec3(0.0, 1.0, 0.0)).normalize()
        val up = right.cross(forward).normalize()
        val tangent = tan(Math.toRadians(verticalFov.coerceIn(30.0, 110.0) * .5))
        val leftMargin = min(24.0, w * .1)
        val top = min(66.0, h * .3)
        val bottom = max(top + h * .25, h - 46.0 - bayRows * 23.0).coerceAtMost(h - 16.0)
        val upperSlope = (1.0 - 2.0 * top / h) * tangent
        val lowerSlope = (2.0 * bottom / h - 1.0) * tangent
        val offsetSlope = ((top + bottom) / h - 1.0) * tangent
        val horizontalSlope = tangent * w / h * (1.0 - 2.0 * leftMargin / w)
        var distance = 4.0
        for (world in points) {
            val p = world.subtract(center)
            val depth = p.dot(forward)
            val vertical = p.dot(up)
            distance = maxOf(distance, 1.0 - depth,
                abs(p.dot(right)) / horizontalSlope - depth,
                (vertical - upperSlope * depth) / (upperSlope + offsetSlope),
                (-vertical - lowerSlope * depth) / (lowerSlope - offsetSlope))
        }
        // A small fixed margin protects labels from rounding without scaling distance with span.
        distance += .35
        return Fit(center.subtract(forward.scale(distance)).add(up.scale(offsetSlope * distance)), distance, pitch, yaw)
    }

    /**
     * Ground-level framing: chooses the view pitch whose fitted eye sits [EYE_CLEARANCE_BLOCKS]
     * above [groundY] (world Y, blocks), so the wings and pylons are seen from below.
     * Every point stays framed exactly as in [fit]. An aircraft whose elevated view is already
     * below the target keeps that view; one that needs a steeper angle than [MIN_PITCH] uses it.
     */
    fun fitFromGround(center: Vec3, points: List<Vec3>, yaw: Float, width: Int, height: Int,
                      verticalFov: Double, bayRows: Int, groundY: Double): Fit {
        val targetY = groundY + EYE_CLEARANCE_BLOCKS
        fun at(pitch: Float) = fit(center, points, yaw, width, height, verticalFov, bayRows, pitch)
        val highest = at(PITCH)
        if (!targetY.isFinite() || highest.position.y <= targetY) return highest
        var below = at(MIN_PITCH)
        if (below.position.y >= targetY) return below
        // Eye height falls as the view tilts upward; keep the bracketing fit that stays above target.
        var above = highest
        repeat(PITCH_SEARCH_STEPS) {
            val middle = at((above.pitch + below.pitch) * .5f)
            if (middle.position.y >= targetY) above = middle else below = middle
        }
        return above
    }

    /**
     * Shortens [fit] along the line from [center] to its eye so the eye stops [clearance] blocks
     * before the nearest obstruction. [obstruction] returns the distance in blocks from [center]
     * at which the segment to a point is first blocked, or null when it is clear. Keeps the view
     * rotation, so the aircraft stays in view while the eye moves in.
     */
    fun unobstructed(center: Vec3, fit: Fit, clearance: Double, obstruction: (Vec3) -> Double?): Fit {
        val offset = fit.position.subtract(center)
        val length = offset.length()
        if (!(length > 1.0e-6)) return fit
        val blocked = obstruction(fit.position) ?: return fit
        val allowed = (blocked - clearance).coerceIn(0.0, length)
        return fit.copy(position = center.add(offset.scale(allowed / length)))
    }
}
