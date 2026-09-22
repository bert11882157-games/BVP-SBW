package com.yourname.berts_vehicle_pack.entity.aircraft;

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimDirectionFrame;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightProfile;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingHandlingProfile;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy;
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategyProvider;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Fixed-wing movement and pilot policy, sharing only the pack's combat/presentation host. */
public abstract class BvpFixedWingAircraft extends ArmoredVehicleEntity
        implements FixedWingFlightStrategyProvider {
    private final FixedWingFlightStrategy flight;

    protected BvpFixedWingAircraft(EntityType<?> type, Level level, String profileId,
                                  BvpAircraftFlightProfiles.Binding binding) {
        this(type, level, profileId, binding.reference(), binding.handling());
    }

    protected BvpFixedWingAircraft(EntityType<?> type, Level level, String profileId,
                                  FixedWingFlightProfile flightProfile,
                                  FixedWingHandlingProfile handlingProfile) {
        super(type, level, profileId);
        flight = new FixedWingFlightStrategy(flightProfile, handlingProfile);
    }

    @Override
    public final FixedWingFlightStrategy createFixedWingFlightStrategy(VehicleEntity vehicle) {
        return flight;
    }

    /** Fixed guns follow the airframe; weapon selection cannot take ownership of the pilot stick. */
    @Override
    public final VehicleAimProfile createVehicleAimProfile(int seatIndex, int selectedWeaponIndex) {
        if (seatIndex > 0 && isHullParentedPassengerWeaponStation()
                && isPassengerWeaponStationWeapon(seatIndex, selectedWeaponIndex)) {
            return VehicleAimProfile.builder(VehicleAimChannel.PASSENGER_WEAPON)
                    .directionFrame(VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL)
                    .rates(Math.abs(getPassengerWeaponYSpeed()), Math.abs(getPassengerWeaponXSpeed()))
                    .yawRange(-getPassengerWeaponMaxYaw(), -getPassengerWeaponMinYaw())
                    .pitchRange(-getPassengerWeaponMaxPitch(), -getPassengerWeaponMinPitch())
                    .lockTolerance(bvpPassengerWeaponAimToleranceDegrees())
                    .defaultMode(VehicleAimMode.PLAYER_LOOK_AIM)
                    .build();
        }
        return null;
    }

    @Override
    protected final boolean usesNativeTurretAimProfile() { return false; }

    @Override
    protected final boolean usesNativePassengerWeaponAimProfile() { return false; }

    @Override
    protected final boolean usesBvpTrackMobilitySystems() { return false; }

    @Override
    public final boolean usesBvpGroundMobilityLimits() { return false; }

}
