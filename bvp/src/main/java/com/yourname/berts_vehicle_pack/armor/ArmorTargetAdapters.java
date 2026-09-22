package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.Entity;

final class ArmorTargetAdapters {
    private ArmorTargetAdapters() {
    }

    static ArmorTarget resolve(Entity entity) {
        ArmoredVehicleEntity vehicle = vehicleFor(entity);
        return vehicle == null ? null : new ArmoredVehicleArmorTarget(vehicle);
    }

    static ArmoredVehicleEntity vehicleFor(Entity entity) {
        if (entity instanceof ArmoredVehicleEntity armored) {
            return armored;
        }
        if (entity != null && entity.m_20202_() instanceof ArmoredVehicleEntity vehicle) {
            return vehicle;
        }
        return null;
    }
}
