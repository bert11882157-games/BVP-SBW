package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/** Exact aircraft-part collision inside ordinary Entity.move, on both logical sides. */
internal object AircraftEntityCollisionService {
    @JvmStatic
    fun tryCollide(entity: Entity, requested: Vec3): Vec3? =
        tryCollide(entity, requested, entity.boundingBox, entity.onGround(), entity.stepHeight.toDouble())

    fun tryCollide(entity: Entity, requested: Vec3, bounds: AABB,
                   grounded: Boolean, stepHeight: Double): Vec3? {
        if (entity.noPhysics || entity.isSpectator || entity is Projectile ||
            entity is VehicleEntity && entity.getAircraftCollisionSnapshot(1F) != null) return null
        val broad = bounds.expandTowards(requested).inflate(stepHeight.coerceAtLeast(0.0) + 1e-7)
        val candidates = entity.level().getEntities(entity, broad) {
            !it.isRemoved && !it.isSpectator && !entity.isPassengerOfSameVehicle(it)
        }
        val aircraft = candidates.filterIsInstance<VehicleEntity>().mapNotNull { vehicle ->
            vehicle.getAircraftCollisionSnapshot(1F)?.let { vehicle to it }
        }
        if (aircraft.isEmpty()) return null
        val parts = aircraft.flatMap { it.second.activeObbs() }
        val native = ArrayList<VoxelShape>()
        for (other in candidates) {
            if (aircraft.any { it.first === other }) continue
            if (entity.canCollideWith(other)) native.add(Shapes.create(other.boundingBox))
        }
        val result = VehicleCollisionSolver.collide(requested, bounds, grounded, stepHeight) { movement, box ->
            AircraftEntityMovement.resolve(movement, box, parts) { axisMovement, axisBounds ->
                Entity.collideBoundingBox(entity, axisMovement, axisBounds, entity.level(), native)
            }
        }
        if (result != requested && EliteDiagnostics.isEnabled(entity.level())) {
            EliteDiagnostics.record(entity, "aircraft_entity_collision", "MOVE",
                "aircraft_ids", aircraft.joinToString(",") { it.first.id.toString() },
                "active_parts", parts.size,
                "requested_x", requested.x, "requested_y", requested.y, "requested_z", requested.z,
                "admitted_x", result.x, "admitted_y", result.y, "admitted_z", result.z)
        }
        return result
    }
}
