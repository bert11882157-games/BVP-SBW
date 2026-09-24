package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Crew-operated stationary grenade launcher; shares the exposed mounted-weapon host. */
public final class Ags30Entity extends UnarmoredMountedWeaponEntity {
    public Ags30Entity(EntityType<Ags30Entity> type, Level level) {
        super(type, level, "ags_30");
    }
}
