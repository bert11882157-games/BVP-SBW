package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class BmptEntity extends ArmoredVehicleEntity {
    public BmptEntity(EntityType<BmptEntity> type, Level world) {
        super(type, world, "bmpt");
    }

    @Override
    protected boolean usesBvpPassengerTurnBodyRefresh() {
        return true;
    }
}
