package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class ChallengerEntity extends ArmoredVehicleEntity {
    public ChallengerEntity(EntityType<ChallengerEntity> type, Level world) {
        super(type, world, "challenger");
    }
}
