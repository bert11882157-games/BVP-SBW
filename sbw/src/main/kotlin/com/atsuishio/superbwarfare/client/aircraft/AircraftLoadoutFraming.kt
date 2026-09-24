package com.atsuishio.superbwarfare.client.aircraft

import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** Fits the equipment view to its usable viewport rather than a padded hull sphere. */
internal object AircraftLoadoutFraming {
    const val PITCH = 12f
    data class Fit(val position: Vec3, val distance: Double)

    fun fit(center: Vec3, points: List<Vec3>, yaw: Float, width: Int, height: Int,
            verticalFov: Double, bayRows: Int): Fit {
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val forward = Vec3.directionFromRotation(PITCH, yaw).normalize()
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
        return Fit(center.subtract(forward.scale(distance)).add(up.scale(offsetSlope * distance)), distance)
    }
}
