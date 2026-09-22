package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class Zsu23_4Entity extends ArmoredVehicleEntity {
    public Zsu23_4Entity(EntityType<Zsu23_4Entity> type, Level world) {
        super(type, world, "zsu23_4");
    }

    @Override
    protected boolean usesBvpPassengerTurnBodyRefresh() {
        return true;
    }
}
