package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Source-authored module points transformed through the live vehicle and turret frames. */
public final class BvpArmorFrames {
    private enum Frame { HULL, TURRET, BARREL }
    // Visual-only M1A1 bustle point: the accepted M1 rack center mapped through four
    // corresponding source turret roof faces (tools/bvp/m1a1_rear_bustle_visual.json).
    private static final Vec M1A1_REAR_BUSTLE_VISUAL = new Vec(0.06639504D, 2.70572537D, 2.39910538D) /* x1.1 ground vehicle scale */;

    /** The accepted world collision transformed into the resolved surface's moving frame. */
    public static final class ImpactAnchor {
        private final UUID vehicleId;
        private final Frame frame;
        private final Vec point;

        private ImpactAnchor(UUID vehicleId, Frame frame, Vec point) {
            this.vehicleId = vehicleId;
            this.frame = frame;
            this.point = point;
        }

        public Vec3 world(ArmoredVehicleEntity vehicle) {
            if (vehicle == null || !vehicleId.equals(vehicle.m_20148_())) return null;
            ArmorTarget target = new ArmoredVehicleArmorTarget(vehicle);
            Vec hull = switch (frame) {
                case HULL -> point;
                case TURRET -> {
                    Vec pivot = target.turretPivot();
                    yield pivot.add(point.subtract(pivot).rotateY(target.turretFrameYaw()));
                }
                case BARREL -> {
                    ArmorCoordinateFrame.BarrelFrame barrel = target.barrelFrame();
                    yield barrel == null ? null : barrel.toHullPoint(point);
                }
            };
            return hull == null ? null : target.armorLocalPointToWorld(hull);
        }
    }

    private BvpArmorFrames() {
    }

    /** Uses already resolved armor volumes; it never searches again behind the hit. */
    public static ImpactAnchor captureImpactAnchor(ProjectileImpactContext context) {
        if (context == null || context.getHitVec() == null
                || !Double.isFinite(context.getHitVec().f_82479_)
                || !Double.isFinite(context.getHitVec().f_82480_)
                || !Double.isFinite(context.getHitVec().f_82481_)) return null;
        BvpImpactVolumeQuery volumes = context.getVehicleImpactVolumes()
                .get(ArmorImpactHandler.VOLUME_PROVIDER_ID, BvpImpactVolumeQuery.class);
        if (volumes == null) return null;
        ArmorHit hit = volumes.resolvedImpactBox();
        if (hit == null) return null;
        ArmorTarget target = volumes.target();
        Vec acceptedHullPoint = target.worldPointToArmorLocal(context.getHitVec());
        Vec point = ArmorHitResolver.pointToBoxFrame(target, hit.plate, acceptedHullPoint);
        if (point == null) return null;
        Frame frame = hit.plate.isBarrelFrame() ? Frame.BARREL
                : hit.plate.isTurretFrame() ? Frame.TURRET : Frame.HULL;
        return new ImpactAnchor(target.vehicle().m_20148_(), frame, point);
    }

    /** Exact turret rack center, or M1A1's source-fitted visual-only rear bustle point. */
    public static Vec3 rearTurretAmmoWorldCenter(ArmoredVehicleEntity vehicle) {
        if (vehicle == null) return null;
        String profileId = vehicle.getArmorProfileId();
        ArmorBox rack = ArmorProfiles.get(profileId).ammoRacks.stream()
                .filter(box -> box.isTurretFrame() && "ammo_rack_00".equals(box.name))
                .findFirst().orElse(null);
        Vec center = rack == null ? "m1a1_abrams".equals(profileId)
                ? M1A1_REAR_BUSTLE_VISUAL : null : rack.centroid();
        if (center == null) return null;
        ArmorTarget target = new ArmoredVehicleArmorTarget(vehicle);
        Vec pivot = target.turretPivot();
        Vec hullPoint = pivot.add(center.subtract(pivot).rotateY(target.turretFrameYaw()));
        return target.armorLocalPointToWorld(hullPoint);
    }
}
