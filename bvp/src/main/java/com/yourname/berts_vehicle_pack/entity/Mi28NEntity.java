package com.yourname.berts_vehicle_pack.entity;

import com.yourname.berts_vehicle_pack.entity.helicopter.HelicopterFlightProfile;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class Mi28NEntity extends Mi24VEntity {
    public Mi28NEntity(EntityType<Mi28NEntity> type, Level world) {
        super(type, world, "mi28n", HelicopterFlightProfile.mi28n());
    }

    @Override
    protected SoundEvent autocannonFireSound() {
        return ModSounds.MI28N_2A42_FIRE.get();
    }
}
