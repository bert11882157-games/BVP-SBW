package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class T90AEntity extends ArmoredVehicleEntity {
    public T90AEntity(EntityType<T90AEntity> type, Level world) {
        super(type, world, "t90a");
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
