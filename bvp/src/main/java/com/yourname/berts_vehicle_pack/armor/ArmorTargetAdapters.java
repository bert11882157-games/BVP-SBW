package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.Entity;

final class ArmorTargetAdapters {
    private ArmorTargetAdapters() {
    }

    static ArmorTarget resolve(Entity entity) {
        ArmoredVehicleEntity vehicle = vehicleFor(entity);
        return vehicle == null || !vehicle.usesBvpArmorResolution()
                ? null : new ArmoredVehicleArmorTarget(vehicle);
    }

    static ArmoredVehicleEntity vehicleFor(Entity entity) {
        if (entity instanceof ArmoredVehicleEntity armored) {
            return armored;
        }
        if (entity != null && entity.m_20202_() instanceof ArmoredVehicleEntity vehicle
                && !vehicle.exposesPassengerToFire(entity)) {
            return vehicle;
        }
        return null;
    }

    /**
     * Crew whose model is hidden inside the vehicle and whose seat is not exposed to fire. Their
     * pick boxes are not part of the vehicle's visible hull, so a contact on them must be
     * re-checked against the vehicle's own collision volumes.
     */
    static boolean isHiddenCrew(ArmoredVehicleEntity vehicle, Entity passenger) {
        return vehicle != null && passenger != null
                && (vehicle.hidePassenger(passenger) || vehicle.isEnclosed(passenger))
                && !vehicle.exposesPassengerToFire(passenger);
    }
}
