package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleLandingImpactPresentation
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Server wheel probes, physical support-pivot settling and one authoritative touchdown producer. */
internal class AircraftWheelSupportService(private val vehicle: VehicleEntity,
                                         private val probe: FixedWingWorldContactProbe) {
    private var definition: AircraftTerrainContact? = null
    private var wheels = emptyList<AircraftWheelGeometry.Wheel>()
    private var previousPoints = emptyMap<String, Vec3>()
    private var expectedPoints = emptyMap<String, Vec3>()
    private var previousTick: Int? = null
    /** Pitch left by the last move while a main tyre carried the aircraft. */
    private var mainSupportPitch: Float? = null
    private val events = AircraftWheelContactEvents()
    private val kernel = AircraftWheelSupportKernel { boxes, movement, offset ->
        val result = probe.sample(movement, offset, terrainBoxes = boxes)
        AircraftWheelSupportKernel.Probe(result.contact, result.complete, result.bodyOverlap)
    }
    val active: Boolean get() = definition != null
    /** Any tyre rested on terrain after the last committed move. */
    var supported = false
        private set

    /**
     * Attitude changes commanded while a main tyre carries the aircraft rotate about that tyre's
     * contact edge instead of the native pivot. Rotating about the pivot would drive main gear
     * behind it into the runway and drop the tail, turning every rotation into a tail strike.
     */
    fun pivotOnMainGear() {
        val pitch = mainSupportPitch ?: return
        mainSupportPitch = null
        val data = vehicle.computed().aircraftTerrainContact ?: return
        if (previousTick != vehicle.tickCount - 1 || vehicle.aircraftWreckImpactTime >= 0 ||
            !data.validWheelContacts() || !data.gearDeployed(vehicle.synchedGearRot)) return
        val delta = Mth.wrapDegrees(vehicle.xRot - pitch).toDouble()
        val roll = vehicle.roll.toDouble()
        if (abs(delta) < 1e-6 || abs(delta) > MAX_PIVOT_DEGREES || abs(roll) > 15.0) return
        val frame = vehicle.getVehicleTransform(1F)
        val pivot = vehicle.rotateOffsetHeight
        val before = AircraftWheelGeometry.pitchAround(frame, Vec3(0.0, pivot, 0.0), roll, -delta)
        // The tyre edge that is lowest in the new attitude keeps its world position.
        val anchor = AircraftWheelGeometry.sample(data, frame)
            .filter { it.group == AircraftWheelContactGroup.MAIN }
            .minByOrNull { it.world.y }?.localSupport ?: return
        val pivoted = AircraftWheelGeometry.pitchAround(before, anchor, roll, delta)
        val correction = AircraftWheelGeometry.originCorrection(before, pivoted, pivot, roll, delta)
        if (!correction.x.isFinite() || !correction.y.isFinite() || !correction.z.isFinite()) return
        vehicle.setPos(vehicle.x + correction.x, vehicle.y + correction.y, vehicle.z + correction.z)
    }

    fun prepare(snapshot: AircraftCollisionSnapshot, requested: Vec3): List<OBBInfo> {
        val data = vehicle.computed().aircraftTerrainContact
        definition = data?.takeIf { vehicle.aircraftWreckImpactTime < 0 &&
            (vehicle.isFixedWingFlightVehicle() || vehicle.vehicleType == VehicleType.HELICOPTER && it.hasWheelVolumes()) && it.validWheelContacts() &&
            it.gearDeployed(vehicle.synchedGearRot) }
        val selected = definition ?: run {
            events.sample(vehicle.level().gameTime, false, emptyMap(), emptyMap())
            previousPoints = emptyMap(); wheels = emptyList(); previousTick = null
            supported = false; mainSupportPitch = null
            return snapshot.terrainInfos()
        }
        wheels = AircraftWheelGeometry.sample(selected, vehicle.getVehicleTransform(1F))
        if (previousTick != vehicle.tickCount - 1) previousPoints = emptyMap()
        expectedPoints = wheels.associate { it.id to it.world.add(requested) }
        return snapshot.terrainInfos().filterNot { it.landingGear } + wheels.map { it.terrainInfo() }
    }

    fun gearEnvelope() = AircraftWheelGeometry.envelope(wheels)

    fun afterMove(motion: AircraftTerrainMotionSolver.Result) {
        val data = definition ?: return
        val frame = vehicle.getVehicleTransform(1F)
        var samples = AircraftWheelGeometry.sample(data, frame)
        val strategy = vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy
        val weight = if (motion.complete && !motion.bodyContact && !vehicle.isWreck &&
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle) == 0)
            strategy?.groundGearSettleWeight(vehicle.deltaMovement.length() * 20.0) ?:
                if (motion.gearGroundContact && vehicle.deltaMovement.y <= .001 &&
                    vehicle.deltaMovement.horizontalDistanceSqr() < .0625) 1.0 else 0.0 else 0.0
        val capture = if (strategy != null && weight > 0.5 && kotlin.math.abs(vehicle.deltaMovement.y) <= 0.03) 0.12 else 0.0
        val poses = if (data.bodyVolumes().any { it.bone != "hull" })
            com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.boneMatrices(vehicle, 1F) else null
        val plan = kernel.plan(data, frame, vehicle.roll.toDouble(), weight, capture, poses)
        var support = plan.contacts
        if (plan.deltaPitch != 0.0 || plan.frame != frame) {
            val correction = AircraftWheelGeometry.originCorrection(frame, plan.frame,
                vehicle.rotateOffsetHeight, vehicle.roll.toDouble(), plan.deltaPitch)
            vehicle.setPos(vehicle.x + correction.x, vehicle.y + correction.y, vehicle.z + correction.z)
            vehicle.acceptWheelSupportPitch((vehicle.xRot + plan.deltaPitch).toFloat())
            val changed = AircraftWheelGeometry.sample(data, vehicle.getVehicleTransform(1F))
            expectedPoints = AircraftWheelGeometry.addRotation(expectedPoints, samples, changed)
            samples = changed
            support = kernel.contacts(samples)
        }
        if (motion.complete && support.complete && support.rows.isNotEmpty() && !motion.bodyContact &&
            vehicle.deltaMovement.y <= 0.0) {
            vehicle.setOnGroundForCollision(true, motion.movement)
            vehicle.verticalCollisionBelow = true
            vehicle.setDeltaMovement(vehicle.deltaMovement.x, 0.0, vehicle.deltaMovement.z)
        }
        val grouped = support.rows.groupBy { it.wheel.group }
        val speeds = AircraftWheelGeometry.closingSpeeds(support.rows, previousPoints,
            wheels.associate { it.id to it.world }, expectedPoints)
        val emitted = events.sample(vehicle.level().gameTime, motion.complete && support.complete,
            grouped.mapValues { (_, rows) -> rows.map { it.point } }, speeds)
        if (!vehicle.isWreck && !motion.bodyContact) emitted.forEach {
            VehicleLandingImpactPresentation.onWheelTouchdown(vehicle, it)
        }
        previousPoints = samples.associate { it.id to it.world }
        previousTick = vehicle.tickCount
        supported = motion.complete && support.complete && support.rows.isNotEmpty()
        mainSupportPitch = if (supported && !motion.bodyContact &&
            support.rows.any { it.wheel.group == AircraftWheelContactGroup.MAIN }) vehicle.xRot else null
        if (EliteDiagnostics.isEnabled(vehicle.level()) &&
            (plan.deltaPitch != 0.0 || emitted.isNotEmpty() || vehicle.tickCount % 20 == 0)) {
            EliteDiagnostics.record(vehicle, "aircraft_wheel_contact", "SUPPORT",
                "main", grouped[AircraftWheelContactGroup.MAIN]?.size ?: 0,
                "nose", grouped[AircraftWheelContactGroup.NOSE]?.size ?: 0,
                "tail", grouped[AircraftWheelContactGroup.TAIL]?.size ?: 0,
                "pitch_adjustment_degrees", plan.deltaPitch, "query_complete", support.complete,
                "touchdown_events", emitted.size)
        }
    }

    private companion object {
        /** Larger jumps are teleports or resets, not rotation on the gear. */
        const val MAX_PIVOT_DEGREES = 10.0
    }
}
