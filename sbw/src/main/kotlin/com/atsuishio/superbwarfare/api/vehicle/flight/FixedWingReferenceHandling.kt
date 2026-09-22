package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Spatial compression with unchanged simulation time.
 * Speed/acceleration scale by s; quadratic drag coefficients scale by 1/s.
 * Angular rates, load factors, damping times and turn duration do not scale.
 */
object FixedWingReferenceHandling {
    @JvmStatic
    fun scale(reference: FixedWingHandlingProfile, lengthScale: Double): FixedWingHandlingProfile {
        require(lengthScale.isFinite() && lengthScale > 0.0)
        val s = lengthScale
        return reference.copy(
            simulationLengthScale = reference.simulationLengthScale * s,
            gravityMps2 = reference.gravityMps2 * s,
            liftReferenceSpeedMps = reference.liftReferenceSpeedMps * s,
            trimSpeedMps = reference.trimSpeedMps * s,
            minimumControlSpeedMps = reference.minimumControlSpeedMps * s,
            maximumSpeedMps = reference.maximumSpeedMps * s,
            maximumIndicatedSpeedMps = reference.maximumIndicatedSpeedMps * s,
            recoverySpeedMps = reference.recoverySpeedMps * s,
            dryAccelerationMps2 = reference.dryAccelerationMps2 * s,
            parasiteDragPerMetre = reference.parasiteDragPerMetre / s,
            inducedDragMps2 = reference.inducedDragMps2 * s,
            stallDragPerMetre = reference.stallDragPerMetre / s,
            airbrakeDragPerMetre = reference.airbrakeDragPerMetre / s,
            rollingResistanceMps2 = reference.rollingResistanceMps2 * s,
            groundBrakingMps2 = reference.groundBrakingMps2 * s,
            overspeedDragPerMetre = reference.overspeedDragPerMetre / s,
            sideDragPerMetre = reference.sideDragPerMetre / s,
            taxiFullSteeringSpeedMps = reference.taxiFullSteeringSpeedMps * s,
            minimumStallStateSpeedMps = reference.minimumStallStateSpeedMps * s,
            propellerPowerReferenceSpeedMps = reference.propellerPowerReferenceSpeedMps * s,
            waveDragPerMetre = reference.waveDragPerMetre / s,
            takeoffHandling = reference.takeoffHandling?.let {
                it.copy(referenceSpeedMps = it.referenceSpeedMps * s)
            },
        )
    }

    @JvmStatic
    fun fromReference(
        reference: FixedWingFlightProfile,
        controls: FixedWingHandlingProfile,
        lengthScale: Double,
    ): FixedWingHandlingProfile {
        val g = 9.80665
        val forcePerSpeedSquared = 0.5 * 1.225 * reference.referenceWingAreaM2 / reference.massKg
        val liftReference = sqrt(g / (forcePerSpeedSquared * reference.maxLiftCoefficient))
        require(controls.trimSpeedMps > liftReference)
        val fullScale = controls.copy(
            simulationLengthScale = 1.0,
            gravityMps2 = g,
            liftReferenceSpeedMps = liftReference,
            maximumSpeedMps = reference.maxEngineSpeedMps,
            maximumIndicatedSpeedMps = reference.maxStructuralSpeedMps,
            stallAngleDegrees = reference.stallAoADegrees,
            recoveryAngleDegrees = reference.stallRecoveryAoADegrees,
            recoveryTicks = reference.stallRecoveryTicks,
            recoverySpeedMps = reference.stallRecoverySpeedMps,
            normalizedLiftSlopePerDegree =
                reference.liftSlopePerRadian * PI / 180.0 / reference.maxLiftCoefficient,
            dryAccelerationMps2 = reference.dryThrustNewtons / reference.massKg,
            afterburnerMultiplier =
                if (reference.afterburnerEnabled) reference.afterburnerMultiplier else 1.0,
            parasiteDragPerMetre = forcePerSpeedSquared * reference.zeroLiftDragCoefficient,
            inducedDragMps2 = g * reference.inducedDragFactor * reference.maxLiftCoefficient,
            stallDragPerMetre = forcePerSpeedSquared * reference.stallDragCoefficient,
        )
        return scale(fullScale, lengthScale)
    }
}
