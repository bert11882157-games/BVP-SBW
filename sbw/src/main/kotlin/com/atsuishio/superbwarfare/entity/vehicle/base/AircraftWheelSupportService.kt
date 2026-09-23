package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftWheelContactGroup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleLandingImpactPresentation
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftTerrainContact
import com.atsuishio.superbwarfare.data.vehicle.subdata.OBBInfo
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.world.phys.Vec3

/** Server wheel probes, physical support-pivot settling and one authoritative touchdown producer. */
internal class AircraftWheelSupportService(private val vehicle: VehicleEntity,
                                         private val probe: FixedWingWorldContactProbe) {
    private var definition: AircraftTerrainContact? = null
    private var wheels = emptyList<AircraftWheelGeometry.Wheel>()
    private var previousPoints = emptyMap<String, Vec3>()
    private var expectedPoints = emptyMap<String, Vec3>()
    private var previousTick: Int? = null
    private val events = AircraftWheelContactEvents()
    private val kernel = AircraftWheelSupportKernel { boxes, movement, offset ->
        val result = probe.sample(movement, offset, terrainBoxes = boxes)
        AircraftWheelSupportKernel.Probe(result.contact, result.complete, result.bodyOverlap)
    }
    val active: Boolean get() = definition != null

    fun prepare(snapshot: AircraftCollisionSnapshot, requested: Vec3): List<OBBInfo> {
        val data = vehicle.computed().aircraftTerrainContact
        definition = data?.takeIf { vehicle.isFixedWingFlightVehicle() && it.validWheelContacts() &&
            it.gearDeployed(vehicle.synchedGearRot) }
        val selected = definition ?: run {
            events.sample(vehicle.level().gameTime, false, emptyMap(), emptyMap())
            previousPoints = emptyMap(); wheels = emptyList(); previousTick = null
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
        val weight = if (motion.complete && !motion.bodyContact && !vehicle.isWreck)
            strategy?.groundGearSettleWeight(vehicle.deltaMovement.length() * 20.0) ?: 0.0 else 0.0
        val plan = kernel.plan(data, frame, vehicle.roll.toDouble(), weight)
        var support = plan.contacts
        if (plan.deltaPitch != 0.0) {
            val correction = AircraftWheelGeometry.originCorrection(frame, plan.frame,
                vehicle.rotateOffsetHeight, vehicle.roll.toDouble(), plan.deltaPitch)
            vehicle.setPos(vehicle.x + correction.x, vehicle.y + correction.y, vehicle.z + correction.z)
            vehicle.acceptWheelSupportPitch((vehicle.xRot + plan.deltaPitch).toFloat())
            val changed = AircraftWheelGeometry.sample(data, vehicle.getVehicleTransform(1F))
            expectedPoints = AircraftWheelGeometry.addRotation(expectedPoints, samples, changed)
            samples = changed
            support = kernel.contacts(samples)
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

}
