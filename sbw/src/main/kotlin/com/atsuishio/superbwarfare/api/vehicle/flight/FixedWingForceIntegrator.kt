package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Velocity integration and specific work accounting in metres, seconds and joules per kilogram. */
internal class FixedWingForceIntegrator {
    var x = 0.0
        private set
    var y = 0.0
        private set
    var z = 0.0
        private set
    var thrustWork = 0.0
        private set
    var gravityWork = 0.0
        private set
    var dragWork = 0.0
        private set
    var liftWork = 0.0
        private set
    var sideWork = 0.0
        private set

    val speedSquared: Double get() = x * x + y * y + z * z
    val speed: Double get() = sqrt(speedSquared)

    fun reset(vx: Double, vy: Double, vz: Double) {
        x = vx
        y = vy
        z = vz
        thrustWork = 0.0
        gravityWork = 0.0
        dragWork = 0.0
        liftWork = 0.0
        sideWork = 0.0
    }

    /** The mean velocity assigns the exact kinetic-energy change to each applied force. */
    fun kick(thrustX: Double, thrustY: Double, thrustZ: Double, gravity: Double, dt: Double) {
        val oldX = x
        val oldY = y
        val oldZ = z
        x += thrustX * dt
        y += (thrustY - gravity) * dt
        z += thrustZ * dt
        val meanX = (oldX + x) * 0.5
        val meanY = (oldY + y) * 0.5
        val meanZ = (oldZ + z) * 0.5
        thrustWork += (meanX * thrustX + meanY * thrustY + meanZ * thrustZ) * dt
        gravityWork -= meanY * gravity * dt
    }

    /** Drag can stop a velocity vector, but cannot reverse it or create kinetic energy. */
    fun drag(acceleration: Double, dt: Double) {
        val before = speedSquared
        if (before <= 1.0E-18) return
        val speed = sqrt(before)
        val nextSpeed = speed / (1.0 + max(0.0, acceleration) / speed * dt)
        val scale = nextSpeed / speed
        x *= scale
        y *= scale
        z *= scale
        dragWork += (speedSquared - before) * 0.5
    }

    /** Final game-speed ceiling preserves direction and accounts for removed kinetic energy. */
    fun limitSpeed(limitMps: Double) {
        require(limitMps.isFinite() && limitMps > 0.0)
        val before = speedSquared
        if (before <= limitMps * limitMps) return
        val scale = limitMps / sqrt(before)
        x *= scale
        y *= scale
        z *= scale
        dragWork += (speedSquared - before) * 0.5
    }

    /**
     * Lift rotates the wing-plane velocity around the span axis. Its spanwise component
     * and total speed are preserved, including signed lift during inverted flight.
     */
    fun lift(acceleration: Double, rightX: Double, rightY: Double, rightZ: Double, dt: Double) {
        val before = speedSquared
        if (before <= 1.0E-18) return
        val spanwise = rightX * x + rightY * y + rightZ * z
        val wingSpeed = sqrt(max(0.0, before - spanwise * spanwise))
        if (wingSpeed <= 1.0E-9) return
        val angle = -acceleration * dt / wingSpeed
        val cosine = cos(angle)
        val sine = sin(angle)
        val oldX = x
        val oldY = y
        val oldZ = z
        x = oldX * cosine + (rightY * oldZ - rightZ * oldY) * sine +
            rightX * spanwise * (1.0 - cosine)
        y = oldY * cosine + (rightZ * oldX - rightX * oldZ) * sine +
            rightY * spanwise * (1.0 - cosine)
        z = oldZ * cosine + (rightX * oldY - rightY * oldX) * sine +
            rightZ * spanwise * (1.0 - cosine)
        liftWork += (speedSquared - before) * 0.5
    }

    /**
     * Passive linear sideslip damping. No roll-angle or roll-rate energy penalty.
     * Exponential integration cannot reverse the spanwise velocity component.
     */
    fun sideDragLinear(coefficient: Double, rightX: Double, rightY: Double, rightZ: Double, dt: Double) {
        val before = speedSquared
        if (before <= 1.0E-18) return
        val lateral = x * rightX + y * rightY + z * rightZ
        val fraction = -kotlin.math.expm1(-max(0.0, coefficient) * sqrt(before) * dt)
        val change = -lateral * fraction
        x += rightX * change
        y += rightY * change
        z += rightZ * change
        sideWork += (speedSquared - before) * 0.5
    }

    /** Passive fuselage drag damps only the spanwise velocity component. */
    fun sideDrag(coefficient: Double, rightX: Double, rightY: Double, rightZ: Double, dt: Double) {
        val before = speedSquared
        val lateral = x * rightX + y * rightY + z * rightZ
        val change = lateral / (1.0 + max(0.0, coefficient) * kotlin.math.abs(lateral) * dt) - lateral
        x += rightX * change
        y += rightY * change
        z += rightZ * change
        sideWork += (speedSquared - before) * 0.5
    }
}
