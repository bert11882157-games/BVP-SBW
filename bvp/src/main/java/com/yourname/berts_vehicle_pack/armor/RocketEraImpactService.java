package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-only typed rocket-to-ERA impact seam.  It is deliberately independent of the normal
 * first ERA penetration query: one accepted rocket impact can spend every active brick whose
 * transformed OBB intersects the fixed impact sphere, including bricks on nearby vehicles.
 */
final class RocketEraImpactService {
    static final double IMPACT_RADIUS_BLOCKS = 0.8D;

    private RocketEraImpactService() {
    }

    static int apply(Level level, Vec3 impactPoint, Projectile projectile, ProjectileArmorEffect shot) {
        if (level == null || level.f_46443_ || !finite(impactPoint) ||
                !ArmorShotClassifier.isTypedBvpRocket(projectile, shot)) {
            return 0;
        }
        double radius = IMPACT_RADIUS_BLOCKS;
        AABB bounds = new AABB(
                impactPoint.f_82479_ - radius, impactPoint.f_82480_ - radius,
                impactPoint.f_82481_ - radius, impactPoint.f_82479_ + radius,
                impactPoint.f_82480_ + radius, impactPoint.f_82481_ + radius);
        int spent = 0;
        for (ArmoredVehicleEntity vehicle : level.m_45976_(ArmoredVehicleEntity.class, bounds)) {
            if (vehicle.m_213877_() || !vehicle.m_6084_() || vehicle.m_9236_() != level) {
                continue;
            }
            ArmorProfile profile = ArmorProfiles.get(vehicle.getArmorProfileId());
            if (profile.eraBoxes.isEmpty()) {
                continue;
            }
            ArmorTarget target = new ArmoredVehicleArmorTarget(vehicle);
            Vec localImpact = target.worldPointToArmorLocal(impactPoint);
            for (ArmorBox era : profile.eraBoxes) {
                if (vehicle.isBvpEraBrickSpent(era.name)) {
                    continue;
                }
                Vec boxFrameImpact = ArmorHitResolver.pointToBoxFrame(target, era, localImpact);
                if (boxFrameImpact != null && era.distanceOutside(boxFrameImpact) <= radius) {
                    vehicle.bvpDetonateEraBrick(era.name);
                    spent++;
                }
            }
        }
        return spent;
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_) && Double.isFinite(value.f_82481_);
    }
}
