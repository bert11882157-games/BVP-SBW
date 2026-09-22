package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class Ah6jEntity extends BvpHelicopterEntity {
    public Ah6jEntity(EntityType<Ah6jEntity> type, Level world) {
        super(type, world, "ah_6j", HelicopterFlightProfile.ah6j());
    }
}
