package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.tools.OBB;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Material admission for profiles whose authored volumes describe projectile collision surfaces. */
public final class BvpProjectileCollision {
    private BvpProjectileCollision() { }

    public static boolean supports(String profileId) {
        // Other profiles retain their coarse collision policy until their surface coverage is verified.
        return "leo2a6".equals(profileId) || "m48a3_elite".equals(profileId);
    }

    public static ProjectileCollisionTarget.Hit clip(ArmoredVehicleEntity vehicle, Vec3 start, Vec3 end) {
        ArmorTarget target = ArmorTargetAdapters.resolve(vehicle);
        if (target == null) return null;
        return clip(target, ArmorProfiles.get(target.armorProfileId()), start, end);
    }

    static ProjectileCollisionTarget.Hit clip(ArmorTarget target, ArmorProfile profile, Vec3 start, Vec3 end) {
        Vec localStart = target.worldPointToArmorLocal(start);
        Vec delta = target.worldPointToArmorLocal(end).subtract(localStart);
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= 1.0E-6D) return null;
        Vec direction = delta.scale(1.0D / distance);
        ArmorHit nearest = null;
        OBB.Part part = OBB.Part.BODY;
        List<List<ArmorBox>> groups = List.of(profile.plates, profile.trackBoxes, profile.moduleBoxes,
                profile.engineBoxes, profile.ammoRacks, profile.sensitiveInternals, profile.eraBoxes);
        for (int index = 0; index < groups.size(); index++) {
            // Zero impact tolerance selects the resolver's minimum numerical skin, never proximity.
            ArmorHit hit = ArmorHitResolver.findFirstBoxOnRay(target, groups.get(index),
                    localStart, direction, distance, 0.0D);
            if (hit == null || (nearest != null && hit.distance >= nearest.distance)) continue;
            nearest = hit;
            part = hit.plate.isTurretFrame() ? OBB.Part.TURRET : OBB.Part.BODY;
            if (index == 1) {
                part = target.armorLocalPointToVehicleLocal(hit.hullImpact).f_82479_ > 0
                        ? OBB.Part.WHEEL_LEFT : OBB.Part.WHEEL_RIGHT;
            } else if (index == 3) {
                part = OBB.Part.MAIN_ENGINE;
            }
        }
        return nearest == null ? null
                : new ProjectileCollisionTarget.Hit(target.armorLocalPointToWorld(nearest.hullImpact), part);
    }
}
