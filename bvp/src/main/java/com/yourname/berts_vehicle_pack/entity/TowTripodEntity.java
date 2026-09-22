package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public final class TowTripodEntity extends UnarmoredMountedWeaponEntity {
    public TowTripodEntity(EntityType<TowTripodEntity> type, Level level) {
        super(type, level, "tow_tripod");
    }
}
