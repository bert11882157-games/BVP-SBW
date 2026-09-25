package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.api.vehicle.presentation.VehicleLandingImpactPresentation
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionRole
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.init.ModDamageTypes
import net.minecraft.world.phys.Vec3

/** Server terrain movement using the shared physical aircraft parts. */
internal class AircraftTerrainCollisionService(private val vehicle: VehicleEntity) {
    private val probe = FixedWingWorldContactProbe(vehicle)
    private val gearImpactGate = AircraftGearImpactGate()
    private val wheelSupport = AircraftWheelSupportService(vehicle, probe)
    private var lastContactTick = Int.MIN_VALUE
    private var lastBodyContactTick = Int.MIN_VALUE
    private var lastGearSupportTick: Int? = null
    private var gearTravelDirection = Vec3.ZERO

    /**
     * World contact that can finish a damaged airframe. Before the wreck state only hull and
     * wing contact counts, so a wing-damaged aircraft rolling out on its wheels is not destroyed
     * by the contact itself; a wreck counts every contact.
     */
    fun recentContact(): Boolean = recent(lastBodyContactTick) || vehicle.isWreck && recent(lastContactTick)

    private fun recent(tick: Int) = tick != Int.MIN_VALUE && vehicle.tickCount - tick in 0..1

    fun move(requested: Vec3) {
        wheelSupport.pivotOnMainGear()
        val snapshot = vehicle.getAircraftCollisionSnapshot(1F) ?: return
        val incoming = vehicle.deltaMovement
        val gearDown = snapshot.parts.any { it.role == AircraftCollisionRole.LANDING_GEAR && it.active }
        val sources = wheelSupport.prepare(snapshot, requested)
        var cells = 0
        val canFollowGear = gearDown && lastGearSupportTick == vehicle.tickCount - 1 &&
            sources.lastOrNull()?.getOBB()?.let {
                AircraftTerrainMotionSolver.acceptsGearContact(Vec3(0.0, 1.0, 0.0), it)
            } == true
        if (requested.x * requested.x + requested.z * requested.z > 1e-12)
            gearTravelDirection = Vec3(requested.x, 0.0, requested.z).normalize()
        val gearReach = if (canFollowGear) AircraftTerrainMotionSolver.gearSupportReach(
            sources.first().getOBB(), if (wheelSupport.active) wheelSupport.gearEnvelope()
            else sources.last().getOBB(), gearTravelDirection) else Vec3.ZERO
        val motion = AircraftTerrainMotionSolver.resolve(requested, vehicle.deltaMovement,
            canFollowGear, gearReach) { remaining, admitted ->
            val sample = probe.sample(remaining, admitted, terrainBoxes = sources)
            cells += sample.cells
            AircraftTerrainMotionSolver.Query(sample.contact, sample.landingGear, sample.complete)
        }
        val admitted = motion.movement
        val bodyContact = motion.bodyContact
        val gearContact = motion.gearContact
        val complete = motion.complete
        val normalSpeedSquared = motion.normalSpeedSquared
        // Only a confirmed wing-only strike is survivable. Re-sweep without the sheared
        // parts so the now-missing wing cannot stop the fuselage or consume its momentum.
        if (bodyContact && complete && !vehicle.isWreck) {
            val core = snapshot.parts.filter { it.active && it.wingSide == 0 &&
                it.role != AircraftCollisionRole.LANDING_GEAR }.map { it.toTerrainInfo() }
            val coreHit = probe.sample(requested, Vec3.ZERO, terrainBoxes = core)
            if (coreHit.complete && coreHit.contact == null && !coreHit.bodyOverlap) {
                var sheared = 0
                for (side in 1..2) {
                    val wing = snapshot.parts.filter { it.active && it.wingSide == side }.map { it.toTerrainInfo() }
                    if (wing.isEmpty()) continue
                    val hit = probe.sample(requested, Vec3.ZERO, terrainBoxes = wing)
                    val contact = hit.contact
                    // A wingtip or stabilizer brushing the runway only scrapes; a real strike
                    // into terrain tears the wing off.
                    if (hit.complete && contact != null && com.atsuishio.superbwarfare.api.vehicle.flight
                            .FixedWingImpactModel.shearsWing(incoming, contact.normal)) sheared = sheared or side
                }
                if (sheared != 0) {
                    val breakup = com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup
                    val before = breakup.mask(vehicle)
                    breakup.detach(vehicle, sheared, incoming)
                    if (breakup.mask(vehicle) != before) {
                        EliteDiagnostics.record(vehicle, "aircraft_terrain_contact", "WING_SHEAR",
                            "sides", sheared, "health", vehicle.health, "incoming_speed", incoming.length())
                        move(requested)
                        return
                    }
                }
            }
        }
        if (bodyContact || gearContact) lastContactTick = vehicle.tickCount
        if (bodyContact) lastBodyContactTick = vehicle.tickCount
        vehicle.setPos(vehicle.x + admitted.x, vehicle.y + admitted.y, vehicle.z + admitted.z)
        vehicle.setDeltaMovement(motion.velocity)
        vehicle.horizontalCollision = motion.horizontal
        vehicle.verticalCollision = motion.vertical
        vehicle.verticalCollisionBelow = motion.below
        vehicle.minorHorizontalCollision = false
        vehicle.setOnGroundForCollision(motion.below, admitted)
        wheelSupport.afterMove(motion)
        // Tyres left resting on the runway by the support settle keep the ground run alive,
        // including a tick whose requested motion only grazed the floor.
        lastGearSupportTick = if (complete && (motion.gearGroundContact || wheelSupport.supported))
            vehicle.tickCount else null
        if (lastGearSupportTick == null) gearTravelDirection = Vec3.ZERO
        val gearImpactSpeed = gearImpactGate.sample(vehicle.level().gameTime, complete,
            motion.gearGroundContact, motion.gearImpactSpeedBlocksPerTick)
        if (gearImpactSpeed > 0.0 && !vehicle.isWreck && !wheelSupport.active) {
            VehicleLandingImpactPresentation.onGearImpact(vehicle, gearImpactSpeed)
        }
        var appliedDamage = 0F
        var destructive = false
        if ((bodyContact || gearContact) && !vehicle.isWreck) {
            val damage = com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingImpactModel.afterWingLoss(
                com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(vehicle),
                complete, bodyContact, motion.gearImpactSpeedBlocksPerTick,
                incoming.lengthSqr() * 400.0, motion.damage)
            destructive = damage.destructive
            if (damage.healthFraction > 0.0 && (destructive || vehicle.collisionCoolDown == 0)) {
                val source = ModDamageTypes.causeVehicleStrikeDamage(vehicle.level().registryAccess(),
                    vehicle, vehicle.lastDriver ?: vehicle)
                val result = vehicle.applyResolvedDamage(ResolvedVehicleDamageRequest(source,
                    (vehicle.getMaxHealth() * damage.healthFraction).toFloat(),
                    modulePolicy = ResolvedVehicleModulePolicy.SKIP_NATIVE, lethal = destructive))
                if (result.accepted) {
                    appliedDamage = result.appliedDamage; vehicle.collisionCoolDown = 4
                    if (vehicle.health <= 0F) com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup
                        .detach(vehicle, 3, incoming)
                }
            }
        }
        if (complete && (bodyContact || motion.gearGroundContact))
            com.atsuishio.superbwarfare.api.vehicle.flight.AircraftFuselageWreck.contact(vehicle, incoming, motion.below)
        if (EliteDiagnostics.isEnabled(vehicle.level()) &&
            (bodyContact || gearContact || !complete || vehicle.tickCount % 20 == 0)) {
            EliteDiagnostics.record(vehicle, "aircraft_terrain_contact", "MOVE",
                "defined_boxes", snapshot.parts.size, "active_boxes", snapshot.parts.count { it.active },
                "terrain_samples", sources.size, "gear_deployed", gearDown,
                "fuselage_contact", bodyContact, "gear_contact", gearContact,
                "gear_ground_contact", motion.gearGroundContact,
                "gear_approach_speed_blocks_per_tick", motion.gearImpactSpeedBlocksPerTick,
                "gear_impact_event", gearImpactSpeed > 0.0,
                "gear_follow_eligible", canFollowGear,
                "gear_step_used", motion.gearStepUsed,
                "support_adjustment_blocks", motion.supportAdjustmentBlocks,
                "normal_speed_squared_mps", normalSpeedSquared, "damage", appliedDamage,
                "destructive", destructive, "query_complete", complete, "query_cells", cells,
                "requested_x", requested.x, "requested_y", requested.y, "requested_z", requested.z,
                "admitted_x", admitted.x, "admitted_y", admitted.y, "admitted_z", admitted.z)
        }
    }
}
