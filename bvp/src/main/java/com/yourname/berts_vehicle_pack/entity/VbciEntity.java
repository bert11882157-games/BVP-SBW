package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class VbciEntity extends ArmoredVehicleEntity {
    public VbciEntity(EntityType<VbciEntity> type, Level world) {
        super(type, world, "vbci");
    }
}
