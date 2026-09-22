package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

interface ArmorTarget {
    ArmoredVehicleEntity vehicle();

    Level level();

    String armorProfileId();

    Vec worldPointToArmorLocal(Vec3 point);

    Vec3 armorLocalPointToWorld(Vec point);

    Vec worldDirectionToArmorLocal(Vec3 direction);

    Vec turretPivot();

    double turretFrameYaw();

    default ArmorCoordinateFrame.BarrelFrame barrelFrame() {
        return null;
    }

    default boolean strictArmorGate(ArmorProfile profile) {
        return !profile.unboxedHitsPenetrate;
    }
}
