package com.yourname.berts_vehicle_pack.entity.helicopter;

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimDirectionFrame;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/** Helicopter host with an explicit physical profile and separately operated hull-mounted gun. */
public final class AuthoredHelicopter extends BvpHelicopterEntity {
    public AuthoredHelicopter(EntityType<AuthoredHelicopter> type, Level level, String vehicleId) {
        super(type, level, vehicleId, requireProfile(vehicleId));
    }

    private static HelicopterFlightProfile requireProfile(String id) {
        return switch (id) {
            case "mi_24a" -> HelicopterFlightProfile.mi24a();
            case "mi_24d" -> HelicopterFlightProfile.mi24d();
            case "mi_26" -> HelicopterFlightProfile.mi26();
            case "ah_1f" -> HelicopterFlightProfile.ah1f();
            default -> throw new IllegalArgumentException("Unknown authored helicopter: " + id);
        };
    }

    @Override
    public boolean usesRotorCoupledHelicopterControls() { return true; }

    @Override
    public VehicleAimProfile createVehicleAimProfile(int seatIndex, int selectedWeaponIndex) {
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
    protected boolean usesNativeTurretAimProfile() { return false; }

    @Override
    protected boolean usesNativePassengerWeaponAimProfile() { return false; }

    @Override
    protected boolean usesBvpTrackMobilitySystems() { return false; }

    @Override
    public boolean usesBvpGroundMobilityLimits() { return false; }
}
