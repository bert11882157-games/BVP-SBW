package com.yourname.berts_vehicle_pack.entity;

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
    private static final int DEFAULT_AUTOCANNON_WEAPON_INDEX = 0;
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

    protected int autocannonWeaponIndex() {
        return DEFAULT_AUTOCANNON_WEAPON_INDEX;
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

    public boolean isBvpAutocannonSelection(int seatIndex, String weaponName) {
        return seatIndex == autocannonSeatIndex() && DEFAULT_AUTOCANNON_WEAPON_NAME.equals(weaponName);
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

    private VehicleWeaponScheduleSnapshot autocannonScheduleSnapshot() {
        return getWeaponScheduleSnapshot(autocannonSeatIndex(), autocannonWeaponIndex());
    }
}
