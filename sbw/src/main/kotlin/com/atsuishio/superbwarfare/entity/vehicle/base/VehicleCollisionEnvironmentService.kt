package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.util.Mth
import net.minecraft.world.entity.MoverType
import net.minecraft.world.phys.Vec3

/** Owns the native collision solver core behind VehicleEntity's movement compatibility facade. */
internal class VehicleCollisionEnvironmentService(
    private val vehicle: VehicleEntity,
) {
    fun collide(requestedMovement: Vec3, allowAirborneStep: Boolean = false): Vec3 {
        if (requestedMovement.lengthSqr() == 0.0) return requestedMovement
        val bounds = vehicle.boundingBox
        val collisions = vehicle.level().getEntityCollisions(vehicle, bounds.expandTowards(requestedMovement))
        return VehicleCollisionSolver.collide(
            requestedMovement, bounds, allowAirborneStep || vehicle.onGround(), vehicle.stepHeight.toDouble(),
        ) { movement, box -> vehicle.resolveCollisionBoundingBox(movement, box, collisions) }
    }

    fun move(movementType: MoverType, requestedMovement: Vec3, allowAirborneStep: Boolean = false) {
        val profiler = vehicle.level().profiler
        profiler.push("move")
        try {
    
            val backedOffMovement = vehicle.backOffFromEdgeForCollision(requestedMovement, movementType)
            val resolved = collide(backedOffMovement, allowAirborneStep)
            if (resolved.lengthSqr() > 1.0E-7) {
                vehicle.setPos(vehicle.x + resolved.x, vehicle.y + resolved.y, vehicle.z + resolved.z)
            }
    
            profiler.popPush("rest")
            val collidedX = !Mth.equal(backedOffMovement.x, resolved.x)
            val collidedZ = !Mth.equal(backedOffMovement.z, resolved.z)
            vehicle.horizontalCollision = collidedX || collidedZ
            vehicle.verticalCollision = backedOffMovement.y != resolved.y
            vehicle.verticalCollisionBelow = vehicle.verticalCollision && backedOffMovement.y < 0.0
            vehicle.minorHorizontalCollision = if (vehicle.horizontalCollision) {
                vehicle.isHorizontalCollisionMinorForCollision(resolved)
            } else {
                false
            }
    
            vehicle.setOnGroundForCollision(vehicle.verticalCollisionBelow, resolved)
            val onPosition = vehicle.getOnPositionForCollision(0.2f)
            val blockState = vehicle.level().getBlockState(onPosition)
            if (vehicle.isRemoved) {
                return
            }
    
            if (vehicle.horizontalCollision) {
                val motion = vehicle.deltaMovement
                vehicle.setDeltaMovement(
                    if (collidedX) 0.0 else motion.x,
                    motion.y,
                    if (collidedZ) 0.0 else motion.z,
                )
            }
    
            val block = blockState.block
            if (backedOffMovement.y != resolved.y) {
                block.updateEntityAfterFallOn(vehicle.level(), vehicle)
            }
            if (vehicle.onGround()) {
                block.stepOn(vehicle.level(), onPosition, blockState, vehicle)
            }
        } finally {
            profiler.pop()
        }
    }
}
