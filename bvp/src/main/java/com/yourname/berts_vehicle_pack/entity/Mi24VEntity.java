package com.yourname.berts_vehicle_pack.entity;

import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimChannel;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimMode;
import com.atsuishio.superbwarfare.api.vehicle.aim.VehicleAimProfile;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleSnapshot;
import com.yourname.berts_vehicle_pack.entity.helicopter.BvpHelicopterEntity;
import com.yourname.berts_vehicle_pack.entity.helicopter.HelicopterFlightProfile;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class Mi24VEntity extends BvpHelicopterEntity {
    private static final int DEFAULT_AUTOCANNON_SEAT_INDEX = 1;
    private static final String DEFAULT_AUTOCANNON_WEAPON_NAME = "Cannon";

    public Mi24VEntity(EntityType<Mi24VEntity> type, Level world) {
        this(type, world, "mi24v", HelicopterFlightProfile.mi24v());
    }

    protected Mi24VEntity(EntityType<? extends Mi24VEntity> type, Level world, String armorProfileId,
                          HelicopterFlightProfile flightProfile) {
        super(type, world, armorProfileId, flightProfile);
    }

    protected SoundEvent autocannonFireSound() {
        return ModSounds.MI24V_YAKB_FIRE.get();
    }

    protected int autocannonSeatIndex() {
        return DEFAULT_AUTOCANNON_SEAT_INDEX;
    }

    /** The cannon's place in the gun seat's weapon list, resolved by name (-1 when that seat does not carry it). */
    protected int autocannonWeaponIndex() {
        return getWeaponIds(autocannonSeatIndex()).indexOf(DEFAULT_AUTOCANNON_WEAPON_NAME);
    }

    /** Whether the weapon at {@code weaponIndex} of {@code seatIndex} is the cannon fired from its own gun seat. */
    protected boolean isAutocannonSeatWeapon(int seatIndex, int weaponIndex) {
        return seatIndex == autocannonSeatIndex() && weaponIndex >= 0
                && DEFAULT_AUTOCANNON_WEAPON_NAME.equals(getGunName(seatIndex, weaponIndex));
    }

    @Override
    protected String bvpHelicopterExtraLog() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return "autocannonHeld=" + (snapshot != null && snapshot.getTriggerHeld())
                + " autocannonStage=" + (snapshot == null ? -1 : snapshot.getVisualStage())
                + " autocannonRpm=" + (snapshot == null ? 0 : snapshot.getCurrentRpm())
                + " autocannonCredits=" + formatDouble(snapshot == null ? 0.0D : snapshot.getShotCredits())
                + " autocannonShotDecision=" + (snapshot == null ? "idle" : snapshot.getLastDecision())
                + " autocannonOverheatTicks=" + (snapshot == null ? 0 : snapshot.getOverheatTicks());
    }

    @Override
    protected boolean usesNativeTurretAimProfile() {
        return false;
    }

    /**
     * The operator's sight drives the nose turret at its authored rates and limits whatever he has selected (the
     * gun follows the sight it is slaved to); the pilot never steers it. Ka-50 replaces this with its pilot-aimed
     * cannon profile.
     */
    @Override
    public VehicleAimProfile createVehicleAimProfile(int seatIndex, int selectedWeaponIndex) {
        if (seatIndex != autocannonSeatIndex() || !hasTurret() || getBarrelPosition() == null) {
            return null;
        }
        return VehicleAimProfile.builder(VehicleAimChannel.TURRET)
                .rates(bvpTurretAimYawRateDegreesPerSecond(), bvpTurretAimPitchRateDegreesPerSecond())
                .yawRange(-getTurretMaxYaw(), -getTurretMinYaw())
                .pitchRange(-getTurretMaxPitch(), -getTurretMinPitch())
                .lockTolerance(bvpTurretAimToleranceDegrees())
                .defaultMode(VehicleAimMode.PLAYER_LOOK_AIM)
                .build();
    }

    @Override
    protected boolean usesNativePassengerWeaponAimProfile() {
        return false;
    }

    @Override
    protected boolean usesBvpTrackMobilitySystems() {
        return false;
    }

    @Override
    protected boolean usesBvpAmmoRackWarnings() {
        return false;
    }

    private void playAutocannonFireSound(String weaponName, float pitch) {
        Vec3 pos = getShootPos(weaponName, 1.0F);
        m_9236_().m_6263_(null, pos.f_82479_, pos.f_82480_, pos.f_82481_,
                autocannonFireSound(), SoundSource.PLAYERS, 0.325F, pitch);
    }

    public void onBvpScheduledAutocannonShot(String weaponName, VehicleWeaponScheduleSnapshot snapshot,
                                              boolean scheduledSoundDue, float scheduledSoundPitch) {
        if (scheduledSoundDue) {
            playAutocannonFireSound(weaponName, scheduledSoundPitch);
        }
    }

    /** The nose gun fired from the operator's seat, or from the pilot's (who shares it, see the armament seats). */
    public boolean isBvpAutocannonSelection(int seatIndex, String weaponName) {
        return (seatIndex == autocannonSeatIndex() || seatIndex == 0)
                && DEFAULT_AUTOCANNON_WEAPON_NAME.equals(weaponName);
    }

    public float getAutocannonBarrelSpinStrength() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot != null && snapshot.getTriggerHeld() && snapshot.getOverheatTicks() <= 0 ? 1.0F : 0.0F;
    }

    public int getBvpAutocannonRpm() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? 0 : snapshot.getCurrentRpm();
    }

    public int getBvpAutocannonStage() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? -1 : snapshot.getVisualStage();
    }

    public boolean getBvpAutocannonHeld() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot != null && snapshot.getTriggerHeld();
    }

    public double getBvpAutocannonShotCredits() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? 0.0D : snapshot.getShotCredits();
    }

    public int getBvpAutocannonOverheatTicks() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? 0 : snapshot.getOverheatTicks();
    }

    public double getBvpAutocannonHeatFraction() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? 0.0D : snapshot.getHeatFraction();
    }

    public String getBvpAutocannonLastShotDecision() {
        VehicleWeaponScheduleSnapshot snapshot = autocannonScheduleSnapshot();
        return snapshot == null ? "idle" : snapshot.getLastDecision();
    }

    /** The operator's schedule, or the pilot's while only the pilot is firing the shared gun. */
    private VehicleWeaponScheduleSnapshot autocannonScheduleSnapshot() {
        int operatorIndex = autocannonWeaponIndex();
        VehicleWeaponScheduleSnapshot operator = operatorIndex < 0
                ? null : getWeaponScheduleSnapshot(autocannonSeatIndex(), operatorIndex);
        if (operator != null && operator.getTriggerHeld() || autocannonSeatIndex() == 0) {
            return operator;
        }
        int pilotIndex = getWeaponIds(0).indexOf(DEFAULT_AUTOCANNON_WEAPON_NAME);
        VehicleWeaponScheduleSnapshot pilot = pilotIndex < 0 ? null : getWeaponScheduleSnapshot(0, pilotIndex);
        return pilot != null && pilot.getTriggerHeld() ? pilot : operator;
    }
}
