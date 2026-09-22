package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.flight.MiG19FixedWingProfile;
import com.yourname.berts_vehicle_pack.entity.aircraft.BvpFixedWingAircraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public final class Mig19Entity extends BvpFixedWingAircraft {
    public Mig19Entity(EntityType<Mig19Entity> type, Level level) {
        super(type, level, "mig19", MiG19FixedWingProfile.PROFILE, MiG19FixedWingProfile.HANDLING);
    }
}
