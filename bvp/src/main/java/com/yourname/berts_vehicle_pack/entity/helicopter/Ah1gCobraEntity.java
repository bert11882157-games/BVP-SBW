package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class Ah1gCobraEntity extends BvpHelicopterEntity {
    public Ah1gCobraEntity(EntityType<Ah1gCobraEntity> type, Level world) {
        super(type, world, "ah_1g_cobra", HelicopterFlightProfile.ah1gCobra());
    }
}
