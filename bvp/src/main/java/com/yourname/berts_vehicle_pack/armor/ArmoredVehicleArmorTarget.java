package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

final class ArmoredVehicleArmorTarget implements ArmorTarget {
    private final ArmoredVehicleEntity vehicle;
    private final boolean mirrorsArmorProfileX;
    private boolean barrelFrameSampled;
    private ArmorCoordinateFrame.BarrelFrame barrelFrame;

    ArmoredVehicleArmorTarget(ArmoredVehicleEntity vehicle) {
        this.vehicle = vehicle;
        String profileId = vehicle.getArmorProfileId();
        this.mirrorsArmorProfileX = ArmorProfiles.mirrorsProfileX(profileId);
    }

    @Override
    public ArmoredVehicleEntity vehicle() {
        return vehicle;
    }

    @Override
    public Level level() {
        return vehicle.m_9236_();
    }

    @Override
    public String armorProfileId() {
        return vehicle.getArmorProfileId();
    }

    @Override
    public Vec worldPointToArmorLocal(Vec3 point) {
        Vec3 local = vehicle.worldToVehicleLocal(point, 1.0F);
        return visualLocalToArmorProfileLocal(
                ArmorCoordinateFrame.sbwVehicleLocalToVisualLocal(local));
    }

    @Override
    public Vec3 armorLocalPointToWorld(Vec point) {
        return vehicle.vehicleLocalToWorld(armorLocalPointToVehicleLocal(point), 1.0F);
    }

    @Override
    public Vec3 armorLocalPointToVehicleLocal(Vec point) {
        Vec local = armorProfileLocalToVisualLocal(point);
        Vec sbwLocal = visualLocalToSbwVehicleLocal(local);
        return new Vec3(sbwLocal.x, sbwLocal.y, sbwLocal.z);
    }

    @Override
    public Vec worldDirectionToArmorLocal(Vec3 direction) {
        Vec3 local = vehicle.worldDirectionToVehicleLocal(direction, 1.0F);
        return visualLocalToArmorProfileLocal(
                ArmorCoordinateFrame.sbwVehicleLocalToVisualLocal(local));
    }

    @Override
    public Vec turretPivot() {
        Vec3 turretPos = vehicle.isHullParentedPassengerWeaponStation()
                ? vehicle.getPassengerWeaponStationPosition() : vehicle.getTurretPos();
        return turretPos == null
                ? Vec.ZERO
                : visualLocalToArmorProfileLocal(
                        ArmorCoordinateFrame.sbwVehicleLocalToVisualLocal(turretPos));
    }

    @Override
    public double turretFrameYaw() {
        if (vehicle.isHullParentedPassengerWeaponStation()) {
            return Mth.m_14177_(vehicle.getGunYRot() + vehicle.getPassengerWeaponStationBaseYawDegrees());
        }
        double visualYaw = Mth.m_14177_(vehicle.m_146908_() - vehicle.getBarrelYRot(1.0F));
        return mirrorsArmorProfileX ? -visualYaw : visualYaw;
    }

    @Override
    public ArmorCoordinateFrame.BarrelFrame barrelFrame() {
        if (!barrelFrameSampled) {
            barrelFrameSampled = true;
            boolean passengerStation = vehicle.isHullParentedPassengerWeaponStation();
            Vec3 turret = passengerStation ? vehicle.getPassengerWeaponStationPosition() : vehicle.getTurretPos();
            Vec3 barrel = passengerStation ? vehicle.getPassengerWeaponStationBarrelPosition() : vehicle.getBarrelPosition();
            if (turret != null && barrel != null) {
                barrelFrame = ArmorCoordinateFrame.barrelFrame(vehicle.getVehicleTransform(1.0F),
                        passengerStation ? vehicle.getPassengerWeaponStationBarrelTransform(1.0F)
                                : vehicle.getBarrelTransform(1.0F), turret, barrel, mirrorsArmorProfileX);
            }
        }
        return barrelFrame;
    }

    @Override
    public boolean strictArmorGate(ArmorProfile profile) {
        return profile.strictArmorGate || ArmorTarget.super.strictArmorGate(profile);
    }

    // T-72-family armor profiles are authored in hit-local space, while the rendered model is mirrored on X.
    // Reflect both points and directions so server hit selection matches the xray/spent-mask brick.
    private Vec visualLocalToArmorProfileLocal(Vec localValue) {
        return mirrorsArmorProfileX ? new Vec(-localValue.x, localValue.y, localValue.z) : localValue;
    }

    private Vec armorProfileLocalToVisualLocal(Vec localValue) {
        return visualLocalToArmorProfileLocal(localValue);
    }

    private Vec visualLocalToSbwVehicleLocal(Vec local) {
        return local.rotateY(180.0D);
    }

}
