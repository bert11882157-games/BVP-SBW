package com.atsuishio.superbwarfare.client.overlay.weapon

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingAtmosphere
import com.atsuishio.superbwarfare.api.vehicle.flight.VehicleFlightInstrumentSnapshot
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.hypot
import kotlin.math.sqrt

/** Fixed-wing instruments from one accepted body-presentation tuple; no independent clock. */
internal object FixedWingHudMetrics {
    fun speedKmh(snapshot: VehicleFlightInstrumentSnapshot?): Double? {
        val motion = acceptedSnapshot(snapshot)?.motion ?: return null
        return (motion.length() * 72.0).takeIf { it.isFinite() }
    }

    /** HUD-only fallback before the first accepted instrument tuple; never interpolate the long arc. */
    fun angle(previous: Float, current: Float, partialTick: Float): Float? {
        if (!previous.isFinite() || !current.isFinite() || !partialTick.isFinite()) return null
        return Mth.rotLerp(partialTick.coerceIn(0F, 1F), previous, current).takeIf { it.isFinite() }
    }

    /** worldToScreen preserves clip W in z; reject its behind/near-plane and offscreen results. */
    fun visibleProjection(point: Vec3, width: Int, height: Int): Boolean =
        width > 0 && height > 0 && point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
                point.z > 1.0E-4 && point.x >= 0.0 && point.x <= width &&
                point.y >= 0.0 && point.y <= height

    fun acceptedSnapshot(snapshot: VehicleFlightInstrumentSnapshot?): VehicleFlightInstrumentSnapshot? {
        val sample = snapshot ?: return null
        val controls = sample.controlSurfaces ?: return null
        return sample.takeIf { controls.serverTick == it.serverTick }
    }

    fun mach(
        snapshot: VehicleFlightInstrumentSnapshot?,
        presentedVehicleY: Double,
        seaLevel: Double,
        simulationLengthScale: Double,
    ): Double? {
        val sample = acceptedSnapshot(snapshot) ?: return null
        if (!simulationLengthScale.isFinite() || simulationLengthScale <= 0.0 ||
            !presentedVehicleY.isFinite() || !seaLevel.isFinite()
        ) return null
        val motion = sample.motion
        if (!motion.x.isFinite() || !motion.y.isFinite() || !motion.z.isFinite()) return null
        val speedMetresPerSecond = hypot(hypot(motion.x, motion.y), motion.z) / simulationLengthScale * 20.0
        val referenceAltitudeMetres = (presentedVehicleY - seaLevel) / simulationLengthScale
        if (!speedMetresPerSecond.isFinite() || !referenceAltitudeMetres.isFinite()) return null
        val temperature = FixedWingAtmosphere.temperatureKelvin(referenceAltitudeMetres)
        val soundSpeed = sqrt(1.4 * 287.05287 * temperature)
        return (speedMetresPerSecond / soundSpeed).takeIf { it.isFinite() && it >= 0.0 }
    }

    /** Signed aerodynamic lift load, not total occupant acceleration. */
    fun signedLiftG(snapshot: VehicleFlightInstrumentSnapshot?): Double? =
        acceptedSnapshot(snapshot)?.fixedWingSignedLiftG?.takeIf { it.isFinite() }
}
