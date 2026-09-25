package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

final class ArmorPenetrationService {
    private ArmorPenetrationService() {
    }

    static Result evaluate(ArmorTarget target, ArmorHit armorHit, ShotTrace trace, ProjectileArmorEffect shot) {
        ArmorBox plate = armorHit.plate;
        Vec localShotDirection = ArmorHitResolver.directionToBoxFrame(target, plate, trace.hullShotDirection).normalize();
        // The entered face's normal: the true triangle normal for a mesh volume.
        Vec plateNormal = armorHit.frameNormal();
        double rawImpactCosine = Math.abs(localShotDirection.dot(plateNormal));
        double impactCosine = Math.max(0.05D, rawImpactCosine);
        double effectiveArmorMm = plate.armorMm / impactCosine;
        double penetrationMm = shot.penetrationMm;
        boolean penetrated = penetrationMm + 1.0E-4D >= effectiveArmorMm;
        return new Result(impactCosine, effectiveArmorMm, penetrationMm, penetrated);
    }

    record Result(double impactCosine, double effectiveArmorMm, double penetrationMm, boolean penetrated) {
    }
}
