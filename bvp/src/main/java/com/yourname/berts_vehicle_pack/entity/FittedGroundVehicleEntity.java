package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Shared armored ground runtime; the registered source owns geometry, systems, and weapon data. */
public final class FittedGroundVehicleEntity extends ArmoredVehicleEntity {
    public FittedGroundVehicleEntity(EntityType<?> type, Level world, String profileId) {
        super(type, world, profileId);
    }

    @Override
    public AABB fitEntityCollisionBounds(AABB nativeBounds) {
        return BvpMovementCollisionBounds.resolve(this, nativeBounds);
    }
}
