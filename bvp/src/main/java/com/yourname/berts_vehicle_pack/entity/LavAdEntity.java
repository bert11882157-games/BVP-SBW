package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class LavAdEntity extends ArmoredVehicleEntity {
    public LavAdEntity(EntityType<LavAdEntity> type, Level world) {
        super(type, world, "lav_ad");
    }
}
