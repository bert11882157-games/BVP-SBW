package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class T72BEntity extends ArmoredVehicleEntity {
    public T72BEntity(EntityType<T72BEntity> type, Level world) {
        super(type, world, "t72b");
    }

    @Override
    protected boolean usesBvpAttachmentZoomDirection() {
        return true;
    }

    @Override
    protected boolean usesBvpPassengerTurnBodyRefresh() {
        return true;
    }
}
