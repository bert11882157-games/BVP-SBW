package com.atsuishio.superbwarfare.api.vehicle.flight

import net.minecraft.world.phys.Vec3

/** Complete motion and minimum instrument channels authored by one server strategy tick. */
data class VehicleFlightTickResult(
    val motion: Vec3,
    val rotorLift: Double,
    val collective: Double,
    val thrust: Double,
    val throttle: Double,
    val bodyYaw: Float,
    val bodyPitch: Float,
    val bodyRoll: Float,
    val motionIncludesGravity: Boolean,
) {
    init {
        require(motion.x.isFinite() && motion.y.isFinite() && motion.z.isFinite()) { "motion must be finite" }
        require(rotorLift.isFinite()) { "rotorLift must be finite" }
        require(collective.isFinite()) { "collective must be finite" }
        require(thrust.isFinite()) { "thrust must be finite" }
        require(throttle.isFinite()) { "throttle must be finite" }
        require(bodyYaw.isFinite() && bodyPitch.isFinite() && bodyRoll.isFinite()) { "attitude must be finite" }
    }
}
