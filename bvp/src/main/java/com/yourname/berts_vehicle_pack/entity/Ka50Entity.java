package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile;
import com.yourname.berts_vehicle_pack.entity.helicopter.HelicopterFlightProfile;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class Ka50Entity extends Mi24VEntity {
    private static final int AUTOCANNON_WEAPON_INDEX = 1;
    private static final float AUTOCANNON_SOFT_YAW_LIMIT_DEGREES = 6.0F;
    private static final float AUTOCANNON_SOFT_PITCH_LIMIT_DEGREES = 5.0F;
    private VehicleAimProfile cameraLockedAutocannonAimProfile;
    private VehicleAimProfile inactiveAutocannonAimProfile;

    public Ka50Entity(EntityType<Ka50Entity> type, Level world) {
        super(type, world, "ka50", HelicopterFlightProfile.ka50());
    }

    @Override
    protected int autocannonSeatIndex() {
        return 0;
    }

    @Override
    protected int autocannonWeaponIndex() {
        return AUTOCANNON_WEAPON_INDEX;
    }

    @Override
    public int getTurretControllerIndex() {
        // SBW's helicopter HUD reads the raw data index, while BVP's cannon slew uses this runtime getter.
        return autocannonSeatIndex();
    }

    @Override
    protected SoundEvent autocannonFireSound() {
        return ModSounds.MI28N_2A42_FIRE.get();
    }

    @Override
    public VehicleAimProfile createVehicleAimProfile(int seatIndex, int selectedWeaponIndex) {
        if (seatIndex != autocannonSeatIndex()) {
            return null;
        }
        if (!hasCurrentAutocannonAimProfile(this.cameraLockedAutocannonAimProfile)) {
            VehicleAimProfile.Builder profile = VehicleAimProfile.builder(VehicleAimChannel.TURRET)
                    .rates(bvpTurretAimYawRateDegreesPerSecond(),
                            bvpTurretAimPitchRateDegreesPerSecond())
                    .yawRange(-getTurretMaxYaw(), -getTurretMinYaw())
                    .pitchRange(-getTurretMaxPitch(), -getTurretMinPitch())
                    .softLimits(AUTOCANNON_SOFT_YAW_LIMIT_DEGREES, AUTOCANNON_SOFT_PITCH_LIMIT_DEGREES)
                    .lockTolerance(bvpTurretAimToleranceDegrees())
                    .defaultMode(VehicleAimMode.INACTIVE)
                    .snapToNeutralWhenInactive(0.0F, 0.0F);
            this.inactiveAutocannonAimProfile = profile
                    .build();
            this.cameraLockedAutocannonAimProfile = profile
                    .defaultMode(VehicleAimMode.PLAYER_LOOK_AIM)
                    .build();
        }
        return selectedWeaponIndex == autocannonWeaponIndex()
                ? this.cameraLockedAutocannonAimProfile
                : this.inactiveAutocannonAimProfile;
    }

    private boolean hasCurrentAutocannonAimProfile(VehicleAimProfile profile) {
        return profile != null && profile.getChannel() == VehicleAimChannel.TURRET
                && Float.compare(profile.getYawRateDegreesPerSecond(), bvpTurretAimYawRateDegreesPerSecond()) == 0
                && Float.compare(profile.getPitchRateDegreesPerSecond(), bvpTurretAimPitchRateDegreesPerSecond()) == 0
                && Float.compare(profile.getMinYaw(), -getTurretMaxYaw()) == 0
                && Float.compare(profile.getMaxYaw(), -getTurretMinYaw()) == 0
                && Float.compare(profile.getMinPitch(), -getTurretMaxPitch()) == 0
                && Float.compare(profile.getMaxPitch(), -getTurretMinPitch()) == 0
                && Float.compare(profile.getSoftYawLimitDegrees(), AUTOCANNON_SOFT_YAW_LIMIT_DEGREES) == 0
                && Float.compare(profile.getSoftPitchLimitDegrees(), AUTOCANNON_SOFT_PITCH_LIMIT_DEGREES) == 0
                && Float.compare(profile.getLockToleranceDegrees(), bvpTurretAimToleranceDegrees()) == 0
                && profile.getDefaultMode() == VehicleAimMode.PLAYER_LOOK_AIM
                && profile.getSnapToNeutralWhenInactive()
                && Float.compare(profile.getNeutralYaw(), 0.0F) == 0
                && Float.compare(profile.getNeutralPitch(), 0.0F) == 0;
    }

}
