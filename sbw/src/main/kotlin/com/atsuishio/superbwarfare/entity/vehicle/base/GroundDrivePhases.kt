package com.atsuishio.superbwarfare.entity.vehicle.base

/**
 * Ordered ground-drive observation barriers. The energy hook observes committed controls;
 * longitudinal sampling observes committed steering; thrust observes committed yaw.
 * Inlining avoids allocating callbacks on the tick path.
 */
internal object GroundDrivePhases {
    inline fun <Controls, Steering, Drive> execute(
        advanceControls: () -> Controls,
        commitControls: (Controls) -> Unit,
        consumePower: () -> Unit,
        prepareSteering: () -> Steering,
        commitSteering: (Steering) -> Unit,
        sampleLongitudinal: () -> Double,
        finishDrive: (Steering, Double) -> Drive,
        commitDrive: (Drive) -> Unit,
        applyThrust: () -> Unit,
    ) {
        commitControls(advanceControls())
        consumePower()
        val steering = prepareSteering()
        commitSteering(steering)
        val longitudinal = sampleLongitudinal()
        commitDrive(finishDrive(steering, longitudinal))
        applyThrust()
    }
}
