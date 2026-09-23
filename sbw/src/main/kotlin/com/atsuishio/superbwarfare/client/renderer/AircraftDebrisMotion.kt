package com.atsuishio.superbwarfare.client.renderer

import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import kotlin.math.sin

/** Client-only inertial debris. Collision is supplied by a loaded-terrain sweep, never chunk loading. */
class AircraftDebrisMotion(position: Vec3, velocity: Vec3, orientation: Quaternionf,
                           private val spin: Vec3, private val phase: Double,
                           private val gravity: Double = 9.80665 / 400.0,
                           private val bounceLimit: Int = 0) {
    var previousPosition = position; private set
    var position = position; private set
    var velocity = velocity; private set
    var previousOrientation = Quaternionf(orientation); private set
    var orientation = Quaternionf(orientation); private set
    var grounded = false; private set
    var age = 0; private set
    var groundedTicks = 0; private set
    var bounces = 0; private set
    var impacted = false; private set
    val expired: Boolean get() = if (bounceLimit > 0) age >= 200 else impacted && groundedTicks >= 40
    data class Contact(val position: Vec3, val normal: Vec3)

    /** Pairwise cosmetic contact never feeds back into the authoritative vehicle. */
    fun separate(offset: Vec3, normal: Vec3) {
        position = position.add(offset)
        val into = velocity.dot(normal)
        if (into < 0) velocity = velocity.subtract(normal.scale(into * 1.2))
    }

    fun tick(collision: (Vec3, Vec3) -> Contact?) {
        previousPosition = position
        previousOrientation.set(orientation)
        age++
        if (impacted) groundedTicks++
        // Contact is transient: a wall strike or leaving a ledge must not suspend debris in air.
        val wasGrounded = grounded
        grounded = false
        velocity = velocity.scale(if (wasGrounded) .94 else .996).add(0.0, -gravity, 0.0)
        var remaining = velocity
        for (sweep in 0..2) {
            val target = position.add(remaining)
            val contact = collision(position, target)
            if (contact == null) { position = target; break }
            impacted = true
            val normal = contact.normal.normalize()
            val distance = remaining.length()
            val fraction = if (distance > 1e-9) (position.distanceTo(contact.position) / distance).coerceIn(0.0, 1.0) else 0.0
            position = contact.position.add(normal.scale(.003))
            val into = velocity.dot(normal)
            val floor = normal.y > .5
            if (floor && into < -.06 && bounces < bounceLimit) {
                val tangent = velocity.subtract(normal.scale(into)).scale(.82)
                velocity = tangent.add(normal.scale((-into * .16).coerceIn(.08, .26)))
                bounces++
                break
            }
            if (into < 0) velocity = velocity.subtract(normal.scale(into))
            if (floor) {
                grounded = true
                if (velocity.lengthSqr() < .000025) velocity = Vec3.ZERO
            }
            remaining = velocity.scale(1 - fraction)
            if (remaining.lengthSqr() < 1e-10) break
        }
        // Smooth changing torque gives a tumbling broken panel without per-frame randomness.
        val wobble = sin(age * .071 + phase)
        if (!grounded) orientation.rotateXYZ((spin.x * (1 + wobble * .45)).toFloat(),
            (spin.y * (1 + sin(age * .093 + phase) * .35)).toFloat(),
            (spin.z * (1 - wobble * .35)).toFloat()).normalize()
    }
}
