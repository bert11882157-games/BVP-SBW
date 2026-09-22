package com.atsuishio.superbwarfare.api.vehicle.pose

import net.minecraft.world.phys.Vec3

/** Server composition only; publication/interpolation remain entity/network responsibilities. */
internal object VehiclePoseComposition {
    fun sample(
        previous: VehiclePoseSnapshot,
        provider: VehiclePoseProvider?,
        flightOwnsAttitude: Boolean,
        nativePitch: Float,
        nativeRoll: Float,
        anchor: Vec3,
        yaw: Float,
    ): VehiclePoseSnapshot? {
        if (provider == null) return null
        if (flightOwnsAttitude) {
            // Retire a previous provider layer once; never run two attitude owners in one tick.
            return if (previous.isIdentity()) null else VehiclePoseSnapshot.IDENTITY.withChassisSample(anchor, yaw)
        }
        val provided = provider.updateVehiclePose(previous)
        require(provided.basePitchDegrees.isFinite() && provided.baseRollDegrees.isFinite() &&
            provided.chassisYawDegrees.isFinite() && provided.pitchDegrees.isFinite() &&
            provided.rollDegrees.isFinite() && provided.groundBias.isFinite() &&
            provided.collisionStepOffset.isFinite() && provided.collisionStepVelocity.isFinite()) {
            "Vehicle pose provider returned non-finite components"
        }
        val layered = if (provider.usesLegacyBasePose()) provided.withBasePose(nativePitch, nativeRoll)
            else provided.withBasePose(0F, 0F)
        val sampled = layered.withChassisSample(anchor, yaw)
        return sampled.takeUnless { it.hasSameChassisSample(previous) }
    }
}
