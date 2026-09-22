package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class T72AEntity extends ArmoredVehicleEntity {
    public T72AEntity(EntityType<T72AEntity> type, Level world) {
        super(type, world, "t72a");
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
