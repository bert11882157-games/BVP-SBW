package com.yourname.berts_vehicle_pack.entity.helicopter;

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProfile;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProvider;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProviders;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSelection;
import com.atsuishio.superbwarfare.api.weapon.ShotResult;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.Ka50Entity;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import com.yourname.berts_vehicle_pack.entity.Mi28NEntity;
import net.minecraft.resources.ResourceLocation;

/** BVP-owned authored profiles and presentation callback for SBW's generic server scheduler. */
public final class BvpAutocannonSchedules implements VehicleWeaponScheduleProvider {
    private static final ResourceLocation PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "autocannon_schedules");

    private BvpAutocannonSchedules() {
    }

    public static void register() {
        VehicleWeaponScheduleProviders.register(PROVIDER_ID, new BvpAutocannonSchedules());
    }

    @Override
    public VehicleWeaponScheduleProfile resolve(VehicleWeaponSelection selection) {
        if (!(selection.getVehicle() instanceof Mi24VEntity helicopter)
                || !helicopter.isBvpAutocannonSelection(selection.getSeatIndex(), selection.getWeaponName())) {
            return null;
        }
        boolean cannon = helicopter instanceof Mi28NEntity || helicopter instanceof Ka50Entity;
        int rpm = selection.getGunData().get(GunProp.RPM);
        if (rpm <= 0 || rpm > 9600) return null;
        // The authored installation owns cadence and one round per event. This provider keeps
        // the helicopter sound/spin callback; it must not replace current data with old rates.
        return new VehicleWeaponScheduleProfile(
                new ResourceLocation(BertsVehiclePack.MODID, cannon ? "2a42_30mm" : "yak_b_12_7"),
                rpm, rpm, 1, cannon ? 1 : 14, null, true, true, 4,
                Math.max(2, (rpm + 1199) / 1200), 1.0F, 1.0F, 1.0F, false);
    }

    @Override
    public void onAcceptedShot(VehicleWeaponSelection selection, ShotResult result,
                               VehicleWeaponScheduleSnapshot snapshot, boolean scheduledSoundDue,
                               float scheduledSoundPitch) {
        if (selection.getVehicle() instanceof Mi24VEntity helicopter) {
            helicopter.onBvpScheduledAutocannonShot(
                    selection.getWeaponName(), snapshot, scheduledSoundDue, scheduledSoundPitch);
        }
    }
}
