package com.yourname.berts_vehicle_pack.entity.aircraft;

import com.yourname.berts_vehicle_pack.entity.BvpMovementCollisionBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Shared aircraft host; registration identity selects a distinct immutable authored flight pair. */
public final class AuthoredFixedWingAircraft extends BvpFixedWingAircraft {
    public AuthoredFixedWingAircraft(EntityType<AuthoredFixedWingAircraft> type, Level level, String vehicleId) {
        super(type, level, vehicleId, BvpAircraftFlightProfiles.requireBinding(
                new ResourceLocation("berts_vehicle_pack", "flight_reference/" + vehicleId),
                new ResourceLocation("berts_vehicle_pack", "flight_handling/" + vehicleId)));
    }

    @Override
    public AABB fitEntityCollisionBounds(AABB nativeBounds) {
        return BvpMovementCollisionBounds.resolve(this, nativeBounds);
    }
}
