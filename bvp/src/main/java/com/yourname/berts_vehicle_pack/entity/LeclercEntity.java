package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class LeclercEntity extends ArmoredVehicleEntity {
    public LeclercEntity(EntityType<LeclercEntity> type, Level world) {
        super(type, world, "leclerc");
    }
}
