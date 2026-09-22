package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class T62AEntity extends ArmoredVehicleEntity {
    public T62AEntity(EntityType<T62AEntity> type, Level world) {
        // The normalized fleet ID is t_62a; retain this source/class name so the
        // old unregistered t62a identity cannot be reintroduced as a duplicate.
        super(type, world, "t_62a");
    }
}
