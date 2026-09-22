package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Physical gun mounts use native collision and hull damage without tank armor/modules. */
public abstract class UnarmoredMountedWeaponEntity extends ArmoredVehicleEntity {
    protected UnarmoredMountedWeaponEntity(EntityType<?> type, Level level, String profileId) {
        super(type, level, profileId);
    }

    @Override
    public boolean usesBvpArmorResolution() { return false; }

    @Override
    public boolean usesBvpGroundMobilityLimits() { return false; }

    @Override
    protected boolean usesBvpTrackMobilitySystems() { return false; }

    @Override
    protected boolean usesBvpAmmoRackWarnings() { return false; }
}
