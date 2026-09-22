package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class T80BEntity extends ArmoredVehicleEntity {
    public T80BEntity(EntityType<T80BEntity> type, Level world) {
        super(type, world, "t80b_obr1976");
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
