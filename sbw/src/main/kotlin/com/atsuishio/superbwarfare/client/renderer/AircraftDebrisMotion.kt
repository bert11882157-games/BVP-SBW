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
    val expired: Boolean get() = if (bounceLimit > 0) age >= 200 else grounded && groundedTicks >= 40

    /** Pairwise cosmetic contact never feeds back into the authoritative vehicle. */
    fun separate(offset: Vec3, normal: Vec3) {
        position = position.add(offset)
        val into = velocity.dot(normal)
        if (into < 0) velocity = velocity.subtract(normal.scale(into * 1.2))
    }

    fun tick(collision: (Vec3, Vec3) -> Vec3?) {
        previousPosition = position
        previousOrientation.set(orientation)
        age++
        if (grounded) { groundedTicks++; return }
        val nextVelocity = velocity.scale(.996).add(0.0, -gravity, 0.0)
        val target = position.add(nextVelocity)
        val contact = collision(position, target)
        if (contact != null) {
            position = contact
            if (bounces < bounceLimit) {
                velocity = Vec3(nextVelocity.x * .55, (kotlin.math.abs(nextVelocity.y) * .16).coerceIn(.08, .26), nextVelocity.z * .55)
                position = position.add(0.0, .003, 0.0)
                bounces++
                return
            }
            velocity = Vec3.ZERO
            grounded = true
            return
        }
        position = target
        velocity = nextVelocity
        // Smooth changing torque gives a tumbling broken panel without per-frame randomness.
        val wobble = sin(age * .071 + phase)
        orientation.rotateXYZ((spin.x * (1 + wobble * .45)).toFloat(),
            (spin.y * (1 + sin(age * .093 + phase) * .35)).toFloat(),
            (spin.z * (1 - wobble * .35)).toFloat()).normalize()
    }
}
