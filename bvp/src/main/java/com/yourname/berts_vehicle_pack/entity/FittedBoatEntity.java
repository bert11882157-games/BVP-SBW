package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Source-fitted boats and craft (DV15, LCAC): the SBW ship engine and the data-driven seats and weapons, native hull damage
 * and collision (no tank armour resolution, ground mobility limits, track systems or ammo-rack warnings).
 */
public class FittedBoatEntity extends UnarmoredMountedWeaponEntity {
    public FittedBoatEntity(EntityType<?> type, Level level, String profileId) {
        super(type, level, profileId);
    }
}
