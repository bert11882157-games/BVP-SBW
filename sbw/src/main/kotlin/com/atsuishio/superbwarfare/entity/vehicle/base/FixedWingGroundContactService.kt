package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleDamageRequest
import com.atsuishio.superbwarfare.api.vehicle.damage.ResolvedVehicleModulePolicy
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingImpactModel
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingContactSweep
import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModSounds
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max

/** Authored wheel support and impact response within the aircraft's single movement transaction. */
internal class FixedWingGroundContactService(private val vehicle: VehicleEntity) {
    private val probe = FixedWingWorldContactProbe(vehicle)
    private val gearProbe = FixedWingGearSupportProbe(vehicle)
    private var lastContactTick = Int.MIN_VALUE
    private var previousGearMinimum = Double.NaN
    private var previousPosition: Vec3? = null
    private var previousPoseTick = Int.MIN_VALUE
    private var gear = emptyList<OBB>()
    private var rotationLift = 0.0
    private var supportedMovement: Vec3? = null
    private var initialSupportFloor: Double? = null
    private var requestedMovement = Vec3.ZERO
    private var nativeCeilingCollision = false
    private var previousBodies: List<FixedWingContactSweep.Body>? = null
    private var initialSupportBodies: List<FixedWingContactSweep.Body>? = null

    fun beginMove(requested: Vec3) {
        supportedMovement = null
        initialSupportFloor = null
        initialSupportBodies = null
        requestedMovement = requested
        nativeCeilingCollision = false
        rotationLift = 0.0
        vehicle.updateOBB()
        gear = vehicle.obb.filter { it.landingGear }.map { it.getOBB().move(Vec3.ZERO) }
        val minimum = gearMinimum(gear) ?: run { gear = emptyList(); return }
        val continuous = previousPoseTick == vehicle.tickCount - 1 &&
            previousPosition?.distanceToSqr(vehicle.position())?.let { it < 1e-10 } == true
        if (vehicle.onGround()) {
            // Rotation about a supported wheel changes the reference-point height, not velocity.
            val rotationDrop = if (continuous && previousGearMinimum.isFinite())
                previousGearMinimum - minimum else 0.0
            // Authored boxes include 5 mm padding below the visible tyre at a neutral spawn.
            rotationLift = (rotationDrop + 0.00501).coerceIn(0.0, 0.5)
            if (continuous && previousGearMinimum.isFinite() && previousBodies?.size == vehicle.obb.size) {
                initialSupportFloor = previousGearMinimum
                initialSupportBodies = previousBodies
            }
        }
    }

    private fun gearMinimum(boxes: List<OBB>): Double? = try {
        boxes.minOfOrNull { FixedWingContactSweep.Body(it).bounds.minY }
    } catch (_: IllegalArgumentException) { null }

    fun constrainMovement(nativeResolved: Vec3, collide: (Vec3) -> Vec3): Vec3 {
        nativeCeilingCollision = requestedMovement.y > 0.0 && nativeResolved.y < requestedMovement.y - 1e-7
        val gearDown = !vehicle.hasFixedWingLandingGear() || vehicle.synchedGearRot <= 0.15f
        if (vehicle.isWreck || !gearDown || abs(vehicle.roll) > 10f || vehicle.xRot !in -20f..10f) {
            return nativeResolved
        }
        if (nativeResolved.y > rotationLift + 1e-6) return nativeResolved
        val result = gearProbe.sample(gear, nativeResolved, rotationLift)
        if (!result.complete && vehicle.tickCount % 20 == 0 && EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "fixed_wing_contact", "SUPPORT_QUERY_UNKNOWN",
                "query_cells", result.cells, "gear_boxes", gear.size)
        }
        val height = result.height ?: return nativeResolved
        val candidate = Vec3(nativeResolved.x, max(nativeResolved.y, height), nativeResolved.z)
        if (candidate.y > 1e-7 && candidate.y > nativeResolved.y + 1e-7) {
            val lift = Vec3(0.0, candidate.y, 0.0)
            // The native AABB can fit under a roof while the authored tail cannot. The reference
            // point correction must also fit before the full admitted path is checked for damage.
            val clearance = probe.sample(lift, Vec3.ZERO)
            if (!clearance.complete || clearance.contact?.normal?.dot(lift)?.let { it < -1e-8 } == true) {
                if (!clearance.complete && vehicle.tickCount % 20 == 0 && EliteDiagnostics.isEnabled(vehicle.level())) {
                    EliteDiagnostics.record(vehicle, "fixed_wing_contact", "SUPPORT_PATH_UNKNOWN",
                        "query_cells", clearance.cells)
                }
                return nativeResolved
            }
        }
        val admitted = if (candidate.y > nativeResolved.y + 1e-7) collide(candidate) else nativeResolved
        // A ceiling or side obstruction cannot be bypassed by the wheel constraint.
        if (admitted.distanceToSqr(candidate) > 1e-12) return nativeResolved
        supportedMovement = admitted
        if (rotationLift > 1e-5 && vehicle.tickCount % 20 == 0 && EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "fixed_wing_contact", "WHEEL_SUPPORT",
                "query_cells", result.cells, "query_complete", result.complete,
                "rotation_allowance", rotationLift, "requested_y", nativeResolved.y, "admitted_y", admitted.y)
        }
        return admitted
    }

    fun recentContact(): Boolean = lastContactTick != Int.MIN_VALUE &&
        vehicle.tickCount >= lastContactTick && vehicle.tickCount - lastContactTick <= 1

    fun afterMove(requested: Vec3, incomingVelocity: Vec3, previousPosition: Vec3) {
        if (vehicle.level().isClientSide || vehicle.isWreck || !vehicle.isFixedWingFlightVehicle()) return
        val resolved = vehicle.position().subtract(previousPosition)
        val supported = supportedMovement?.distanceToSqr(resolved)?.let { it < 1e-10 } == true
        if (supported) {
            vehicle.setOnGroundForCollision(true, resolved)
            vehicle.verticalCollisionBelow = true
            vehicle.setDeltaMovement(vehicle.deltaMovement.x, 0.0, vehicle.deltaMovement.z)
            lastContactTick = vehicle.tickCount
        }
        vehicle.updateOBB()
        previousGearMinimum = gearMinimum(vehicle.obb.filter { it.landingGear }.map { it.getOBB() }) ?: Double.NaN
        previousBodies = try { vehicle.obb.map { FixedWingContactSweep.Body(it.getOBB()) } }
            catch (_: IllegalArgumentException) { null }
        this.previousPosition = vehicle.position()
        previousPoseTick = vehicle.tickCount
        val clippedX = abs(requested.x - resolved.x) > 1e-7
        val clippedY = abs(requested.y - resolved.y) > 1e-7
        val clippedZ = abs(requested.z - resolved.z) > 1e-7
        // Rebase current boxes to the pre-move origin and sweep only the path the world solver
        // admitted. A blocked request must not damage the aircraft against unreachable terrain.
        val poseLift = if (supported) max(0.0, resolved.y) else 0.0
        val observed = if (requested.lengthSqr() > 1e-8 || supported) {
            probe.sample(resolved, previousPosition.subtract(vehicle.position()),
                initialSupportFloor.takeIf { poseLift > 0.0 }, initialSupportBodies)
        } else null
        val authored = observed?.contact
        if (observed != null && !observed.complete && vehicle.tickCount % 20 == 0 &&
            EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "fixed_wing_contact", "QUERY_UNKNOWN",
                "query_cells", observed.cells, "empty_sections", observed.emptySections,
                "boxes", vehicle.obb.size, "gear_boxes", vehicle.obb.count { it.landingGear })
        }
        val bodyOverlap = observed?.bodyOverlap == true
        if (!clippedX && !clippedY && !clippedZ && authored == null && !bodyOverlap) return
        lastContactTick = vehicle.tickCount
        val speedSquared = incomingVelocity.lengthSqr() * 400.0
        val clippedNormalSquared = ((if (clippedX) incomingVelocity.x * incomingVelocity.x else 0.0) +
            (if (clippedY && (!supported || incomingVelocity.y < 0.0 || nativeCeilingCollision))
                incomingVelocity.y * incomingVelocity.y else 0.0) +
            (if (clippedZ) incomingVelocity.z * incomingVelocity.z else 0.0)) * 400.0
        val authoredNormal = authored?.let { max(0.0, -incomingVelocity.dot(it.normal)) * 20.0 } ?: 0.0
        val normalSquared = max(clippedNormalSquared, authoredNormal * authoredNormal).coerceAtMost(speedSquared)
        if (!speedSquared.isFinite() || !normalSquared.isFinite()) return
        val gearDown = !vehicle.hasFixedWingLandingGear() || vehicle.synchedGearRot <= 0.15f
        val safeAttitude = gearDown && abs(vehicle.roll) <= 10f && vehicle.xRot in -20f..10f
        val safeNativeSupport = !clippedX && !clippedZ &&
            (supported || (clippedY && requested.y < 0.0))
        val safeAuthoredSupport = !bodyOverlap &&
            (authored == null || (observed.landingGear && authored.normal.y >= 0.7))
        val safeGear = !nativeCeilingCollision && safeAttitude && safeAuthoredSupport &&
            (safeNativeSupport || (authored != null && observed.landingGear && authored.normal.y >= 0.7))
        val damage = FixedWingImpactModel.evaluate(normalSquared, speedSquared, safeGear)
        if (damage.healthFraction <= 0.0 || (!damage.destructive && vehicle.collisionCoolDown > 0)) return
        val source = ModDamageTypes.causeVehicleStrikeDamage(vehicle.level().registryAccess(),
            vehicle, vehicle.lastDriver ?: vehicle)
        val result = vehicle.applyResolvedDamage(ResolvedVehicleDamageRequest(source,
            (vehicle.getMaxHealth() * damage.healthFraction).toFloat(),
            modulePolicy = ResolvedVehicleModulePolicy.SKIP_NATIVE, lethal = damage.destructive))
        if (result.accepted) {
            vehicle.collisionCoolDown = 4
            vehicle.level().playSound(null, vehicle, ModSounds.VEHICLE_STRIKE.get(), vehicle.soundSource, 1f, 1f)
        }
        if (EliteDiagnostics.isEnabled(vehicle.level())) {
            EliteDiagnostics.record(vehicle, "fixed_wing_contact", "IMPACT",
                "normal_speed_squared_mps", normalSquared, "total_speed_squared_mps", speedSquared,
                "safe_gear", safeGear, "destructive", damage.destructive,
                "authored_contact", authored != null, "body_overlap", bodyOverlap,
                "wheel_support", supported, "rotation_lift", poseLift,
                "query_complete", observed?.complete,
                "query_cells", observed?.cells, "empty_sections", observed?.emptySections,
                "accepted", result.accepted, "damage", result.appliedDamage)
        }
    }
}
