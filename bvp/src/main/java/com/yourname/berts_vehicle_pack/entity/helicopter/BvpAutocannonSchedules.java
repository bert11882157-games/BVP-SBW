package com.yourname.berts_vehicle_pack.entity.helicopter;

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProfile;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProvider;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleProviders;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSelection;
import com.atsuishio.superbwarfare.api.weapon.ShotResult;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.Ka50Entity;
import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import com.yourname.berts_vehicle_pack.entity.Mi28NEntity;
import net.minecraft.resources.ResourceLocation;

/** BVP-owned authored profiles and presentation callback for SBW's generic server scheduler. */
public final class BvpAutocannonSchedules implements VehicleWeaponScheduleProvider {
    private static final ResourceLocation PROVIDER_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "autocannon_schedules");
    private static final VehicleWeaponScheduleProfile YAK_B = VehicleWeaponScheduleProfile.fixedWindow(
            new ResourceLocation(BertsVehiclePack.MODID, "yak_b_12_7"),
            2400, 1200, 2, 14, 100, 80, 80, false);
    private static final VehicleWeaponScheduleProfile TWO_A_42 = VehicleWeaponScheduleProfile.fixedWindow(
            new ResourceLocation(BertsVehiclePack.MODID, "2a42_30mm"),
            500, 500, 1, 1, 30, 80, 80, false);

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
        return helicopter instanceof Mi28NEntity || helicopter instanceof Ka50Entity ? TWO_A_42 : YAK_B;
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
