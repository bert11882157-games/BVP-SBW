package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.api.projectile.impact.VehicleImpactVolumeView;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.NearBox;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.RaySnap;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.InternalModuleHits;
import com.yourname.berts_vehicle_pack.armor.ArmorModuleResolver.ModuleHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;

import java.util.Objects;

/**
 * One server-side view over BVP's authored nonphysical volumes for a coarse vehicle impact.
 * Geometry is evaluated lazily at most once per category for the original trace and one ERA restart trace.
 */
final class BvpImpactVolumeQuery implements VehicleImpactVolumeView {
    private final ArmorTarget target;
    private final ArmorProfile profile;
    private final ShotTrace initialTrace;
    private final TraceVolumes initialVolumes = new TraceVolumes();
    private ShotTrace secondaryTrace;
    private TraceVolumes secondaryVolumes;

    private BvpImpactVolumeQuery(ArmorTarget target, ArmorProfile profile, ShotTrace initialTrace) {
        this.target = target;
        this.profile = profile;
        this.initialTrace = initialTrace;
    }

    static BvpImpactVolumeQuery create(ProjectileImpactContext context) {
        ArmorTarget target = ArmorTargetAdapters.resolve(context.getTarget());
        if (target == null) {
            return null;
        }
        ArmorProfile profile = ArmorProfiles.get(target.armorProfileId());
        return new BvpImpactVolumeQuery(target, profile,
                ArmorHitResolver.trace(target, context.getProjectile(), context.getHitVec()));
    }

    ArmorTarget target() {
        return target;
    }

    ArmorProfile profile() {
        return profile;
    }

    ShotTrace initialTrace() {
        return initialTrace;
    }

    ArmorHit eraHit(ShotTrace trace, double impactTolerance) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.eraResolved || Double.compare(volumes.eraTolerance, impactTolerance) != 0) {
            // ERA is a surface contact at the accepted collision point. A long backtrace can
            // cross front bricks before a projectile whose actual hit is on the rear turret.
            ArmorHitResolver.BoxQuery query = ArmorHitResolver.findNearestBoxAtImpact(
                    target, profile.eraBoxes, trace.hullImpactFallback, impactTolerance);
            volumes.eraHit = query.hit();
            volumes.nearestEra = query.nearest();
            volumes.eraTolerance = impactTolerance;
            volumes.eraResolved = true;
        }
        return volumes.eraHit;
    }

    NearBox nearestEraToImpact(ShotTrace trace, double impactTolerance) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.eraResolved || Double.compare(volumes.eraTolerance, impactTolerance) != 0) {
            eraHit(trace, impactTolerance);
        }
        return volumes.nearestEra;
    }

    ArmorHit armorHit(ShotTrace trace) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.armorResolved) {
            ArmorHitResolver.BoxQuery query = ArmorHitResolver.findBestBoxWithNearestFallback(
                    target, profile.plates, trace,
                    ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
            volumes.armorHit = query.hit();
            volumes.nearestArmor = query.nearest();
            volumes.armorResolved = true;
        }
        return volumes.armorHit;
    }

    /** Previously resolved contact only; presentation must not start another armor ray. */
    ArmorHit resolvedImpactBox() {
        if (secondaryVolumes != null && secondaryVolumes.armorResolved) {
            return secondaryVolumes.resolvedContact();
        }
        if (initialVolumes.armorResolved) {
            return initialVolumes.resolvedContact();
        }
        return initialVolumes.eraResolved ? initialVolumes.eraHit : null;
    }

    /** Nearest plate to a shot whose ray crossed no plate; evaluated once per trace. */
    RaySnap nearestPlateToRay(ShotTrace trace, double maxGap) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.snapResolved || Double.compare(volumes.snapMaxGap, maxGap) != 0) {
            volumes.snap = ArmorHitResolver.findNearestBoxToRay(target, profile.plates,
                    trace.rayStart, trace.hullShotDirection,
                    ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, maxGap);
            volumes.snapMaxGap = maxGap;
            volumes.snapResolved = true;
        }
        return volumes.snap;
    }

    NearBox nearestArmorToImpact(ShotTrace trace) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.armorResolved) {
            armorHit(trace);
        }
        return volumes.nearestArmor;
    }

    ModuleHit directTrackHit(ShotTrace trace) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.trackResolved) {
            volumes.trackHit = ArmorModuleResolver.findDirectTrackHit(target, profile, trace);
            volumes.trackResolved = true;
        }
        return volumes.trackHit;
    }

    ModuleHit directModuleHit(ShotTrace trace) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.moduleResolved) {
            volumes.moduleHit = ArmorModuleResolver.findDirectModuleHit(target, profile, trace);
            volumes.moduleResolved = true;
        }
        return volumes.moduleHit;
    }

    InternalModuleHits internalHits(ShotTrace trace, ArmorHit armorHit) {
        TraceVolumes volumes = volumes(trace);
        if (!volumes.internalsResolved || volumes.internalArmorHit != armorHit) {
            volumes.internalHits = ArmorModuleResolver.findInternalHits(target, profile, armorHit, trace);
            volumes.internalArmorHit = armorHit;
            volumes.internalsResolved = true;
        }
        return volumes.internalHits;
    }

    private TraceVolumes volumes(ShotTrace trace) {
        Objects.requireNonNull(trace, "trace");
        if (trace == initialTrace) {
            return initialVolumes;
        }
        if (trace != secondaryTrace) {
            secondaryTrace = trace;
            secondaryVolumes = new TraceVolumes();
        }
        return secondaryVolumes;
    }

    private static final class TraceVolumes {
        private boolean eraResolved;
        private double eraTolerance;
        private ArmorHit eraHit;
        private NearBox nearestEra;
        private boolean armorResolved;
        private ArmorHit armorHit;
        private NearBox nearestArmor;
        private boolean trackResolved;
        private ModuleHit trackHit;
        private boolean moduleResolved;
        private ModuleHit moduleHit;
        private boolean internalsResolved;
        private ArmorHit internalArmorHit;
        private InternalModuleHits internalHits;
        private boolean snapResolved;
        private double snapMaxGap;
        private RaySnap snap;

        private ArmorHit resolvedContact() {
            if (armorHit != null) {
                return armorHit;
            }
            return snap == null ? null : snap.hit();
        }
    }
}
