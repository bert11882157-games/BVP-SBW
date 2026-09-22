package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategyProvider;
import com.atsuishio.superbwarfare.api.vehicle.flight.MiG19FixedWingProfile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public final class Mig19Entity extends ArmoredVehicleEntity implements FixedWingFlightStrategyProvider {
    private final FixedWingFlightStrategy fixedWingFlightStrategy =
            new FixedWingFlightStrategy(MiG19FixedWingProfile.PROFILE);

    public Mig19Entity(EntityType<Mig19Entity> type, Level level) {
        super(type, level, "mig19");
    }

    @Override
    public FixedWingFlightStrategy createFixedWingFlightStrategy(VehicleEntity vehicle) {
        return fixedWingFlightStrategy;
    }
}
