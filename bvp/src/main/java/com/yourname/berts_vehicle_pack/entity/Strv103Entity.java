package com.yourname.berts_vehicle_pack.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Strv 103C: turretless. The 105 mm gun is fixed in the hull and laid in traverse by turning the hull on its tracks
 * (the data's TurretYawRange keeps the "turret", the gun cradle, within half a degree of the hull) and in elevation
 * through the suspension range (TurretPitchRange -11/+16). There is no turret to throw when it cooks off.
 */
public class Strv103Entity extends ArmoredVehicleEntity {
    public Strv103Entity(EntityType<Strv103Entity> type, Level world) {
        super(type, world, "strv_103");
    }

    @Override
    public boolean allowsTurretEjection() {
        return false;
    }
}
