package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class ArmorHitResolver {
    static final double ARMOR_RAY_BACKTRACE_BLOCKS = 4.0D;
    static final double ARMOR_RAY_DISTANCE_BLOCKS = 12.0D;

    private ArmorHitResolver() {
    }

    static ShotTrace trace(ArmorTarget target, Projectile projectile, Vec3 hitVec) {
        Vec3 worldShotDirection = shotDirection(projectile, hitVec);
        Vec hullShotDirection = target.worldDirectionToArmorLocal(worldShotDirection).normalize();
        Vec rayStart = target.worldPointToArmorLocal(new Vec3(
                hitVec.f_82479_ - worldShotDirection.f_82479_ * ARMOR_RAY_BACKTRACE_BLOCKS,
                hitVec.f_82480_ - worldShotDirection.f_82480_ * ARMOR_RAY_BACKTRACE_BLOCKS,
                hitVec.f_82481_ - worldShotDirection.f_82481_ * ARMOR_RAY_BACKTRACE_BLOCKS
        ));
        Vec hullImpactFallback = target.worldPointToArmorLocal(hitVec);
        return new ShotTrace(hitVec, hullShotDirection, rayStart, hullImpactFallback);
    }

    static ArmorHit findBestBox(ArmorTarget target, List<ArmorBox> boxes, ShotTrace trace,
                                double maxDistance, double impactTolerance) {
        ArmorHit hit = findFirstBoxOnRay(target, boxes, trace.rayStart, trace.hullShotDirection,
                maxDistance, impactTolerance);
        if (hit == null) {
            hit = findBoxNearImpact(target, boxes, trace.hullImpactFallback, impactTolerance);
        }
        return hit;
    }

    static ArmorHit findBoxNearImpact(ArmorTarget target, List<ArmorBox> boxes,
                                      Vec hullImpact, double impactTolerance) {
        ArmorHit best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorBox box : boxes) {
            Vec frameImpact = pointToBoxFrame(target, box, hullImpact);
            if (frameImpact == null) continue;
            double distance = box.distanceOutside(frameImpact);
            if (distance <= impactTolerance && distance < bestDistance) {
                best = new ArmorHit(box, frameImpact, hullImpact, 0.0D);
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Preserves the normal ray-first rule while retaining nearest fallback data without a second scan. */
    static BoxQuery findBestBoxWithNearestFallback(ArmorTarget target, List<ArmorBox> boxes, ShotTrace trace,
                                                   double maxDistance, double impactTolerance) {
        ArmorHit rayHit = findFirstBoxOnRay(target, boxes, trace.rayStart, trace.hullShotDirection,
                maxDistance, impactTolerance);
        if (rayHit != null) {
            return new BoxQuery(rayHit, null);
        }

        ArmorBox fallbackBox = null;
        Vec fallbackFrame = null;
        double fallbackDistance = Double.MAX_VALUE;
        NearBox nearest = null;
        for (ArmorBox box : boxes) {
            Vec frameImpact = pointToBoxFrame(target, box, trace.hullImpactFallback);
            if (frameImpact == null) continue;
            double distance = box.distanceOutside(frameImpact);
            if (nearest == null || distance < nearest.distance) {
                nearest = new NearBox(box, distance);
            }
            if (distance <= impactTolerance && distance < fallbackDistance) {
                fallbackBox = box;
                fallbackFrame = frameImpact;
                fallbackDistance = distance;
            }
        }
        ArmorHit fallbackHit = fallbackBox == null ? null
                : new ArmorHit(fallbackBox, fallbackFrame, trace.hullImpactFallback, 0.0D);
        return new BoxQuery(fallbackHit, nearest);
    }

    /** Resolves the ray/fallback hit and nearest coarse-impact volume in one stable-order traversal. */
    static BoxQuery findBestAndNearestBox(ArmorTarget target, List<ArmorBox> boxes, ShotTrace trace,
                                          double maxDistance, double impactTolerance) {
        Vec direction = trace.hullShotDirection.normalize();
        boolean hasDirection = direction.length() >= 1.0E-6D;
        double inflation = Math.min(0.03D, Math.max(0.005D, impactTolerance * 0.1D));
        ArmorHit rayHit = null;
        ArmorBox fallbackBox = null;
        Vec fallbackFrame = null;
        double fallbackDistance = Double.MAX_VALUE;
        NearBox nearest = null;

        for (ArmorBox box : boxes) {
            Vec frameFallback = pointToBoxFrame(target, box, trace.hullImpactFallback);
            if (frameFallback == null) continue;
            double distanceOutside = box.distanceOutside(frameFallback);
            if (nearest == null || distanceOutside < nearest.distance) {
                nearest = new NearBox(box, distanceOutside);
            }
            if (distanceOutside <= impactTolerance && distanceOutside < fallbackDistance) {
                fallbackBox = box;
                fallbackFrame = frameFallback;
                fallbackDistance = distanceOutside;
            }

            if (!hasDirection) {
                continue;
            }
            Vec frameStart = pointToBoxFrame(target, box, trace.rayStart);
            Vec frameDirection = directionToBoxFrame(target, box, direction).normalize();
            double distance = box.rayHitDistance(frameStart, frameDirection, maxDistance, inflation);
            if (Double.isFinite(distance) && distance >= 0.0D
                    && (rayHit == null || distance < rayHit.distance)) {
                Vec frameImpact = frameStart.add(frameDirection.scale(distance));
                Vec hullImpact = pointToHullFrame(target, box, frameImpact);
                rayHit = new ArmorHit(box, frameImpact, hullImpact, distance);
            }
        }
        ArmorHit hit = rayHit;
        if (hit == null && fallbackBox != null) {
            hit = new ArmorHit(fallbackBox, fallbackFrame, trace.hullImpactFallback, 0.0D);
        }
        return new BoxQuery(hit, nearest);
    }

    static ArmorHit findFirstBoxOnRay(ArmorTarget target, List<ArmorBox> boxes,
                                      Vec hullStart, Vec hullDirection, double maxDistance,
                                      double impactTolerance) {
        Vec direction = hullDirection.normalize();
        if (direction.length() < 1.0E-6D) {
            return null;
        }
        ArmorHit best = null;
        double inflation = Math.min(0.03D, Math.max(0.005D, impactTolerance * 0.1D));
        for (ArmorBox box : boxes) {
            Vec frameStart = pointToBoxFrame(target, box, hullStart);
            if (frameStart == null) continue;
            Vec frameDirection = directionToBoxFrame(target, box, direction).normalize();
            double distance = box.rayHitDistance(frameStart, frameDirection, maxDistance, inflation);
            if (Double.isFinite(distance) && distance >= 0.0D && (best == null || distance < best.distance)) {
                Vec frameImpact = frameStart.add(frameDirection.scale(distance));
                Vec hullImpact = pointToHullFrame(target, box, frameImpact);
                best = new ArmorHit(box, frameImpact, hullImpact, distance);
            }
        }
        return best;
    }

    static Vec directionToBoxFrame(ArmorTarget target, ArmorBox box, Vec hullDirection) {
        if (box.isBarrelFrame()) {
            ArmorCoordinateFrame.BarrelFrame frame = target.barrelFrame();
            return frame == null ? Vec.ZERO : frame.toBarrelDirection(hullDirection);
        }
        return box.isTurretFrame() ? hullDirection.rotateY(-target.turretFrameYaw()) : hullDirection;
    }

    static Vec pointToBoxFrame(ArmorTarget target, ArmorBox box, Vec hullPoint) {
        if (box.isBarrelFrame()) {
            ArmorCoordinateFrame.BarrelFrame frame = target.barrelFrame();
            return frame == null ? null : frame.toBarrelPoint(hullPoint);
        }
        if (!box.isTurretFrame()) {
            return hullPoint;
        }
        Vec pivot = target.turretPivot();
        return pivot.add(hullPoint.subtract(pivot).rotateY(-target.turretFrameYaw()));
    }

    private static Vec pointToHullFrame(ArmorTarget target, ArmorBox box, Vec framePoint) {
        if (box.isBarrelFrame()) {
            ArmorCoordinateFrame.BarrelFrame frame = target.barrelFrame();
            return frame == null ? null : frame.toHullPoint(framePoint);
        }
        if (!box.isTurretFrame()) {
            return framePoint;
        }
        Vec pivot = target.turretPivot();
        return pivot.add(framePoint.subtract(pivot).rotateY(target.turretFrameYaw()));
    }

    private static Vec3 shotDirection(Projectile projectile, Vec3 hitVec) {
        Vec3 velocity = projectile.m_20184_();
        double length = vec3Length(velocity);
        if (length > 1.0E-6D) {
            return normalizeVec3(velocity, length);
        }
        Vec3 fromShellToHit = new Vec3(
                hitVec.f_82479_ - projectile.m_20185_(),
                hitVec.f_82480_ - projectile.m_20186_(),
                hitVec.f_82481_ - projectile.m_20189_()
        );
        length = vec3Length(fromShellToHit);
        return length > 1.0E-6D ? normalizeVec3(fromShellToHit, length) : new Vec3(0.0D, 0.0D, 1.0D);
    }

    private static double vec3Length(Vec3 value) {
        return Math.sqrt(value.f_82479_ * value.f_82479_
                + value.f_82480_ * value.f_82480_
                + value.f_82481_ * value.f_82481_);
    }

    private static Vec3 normalizeVec3(Vec3 value, double length) {
        return new Vec3(value.f_82479_ / length, value.f_82480_ / length, value.f_82481_ / length);
    }

    static final class ShotTrace {
        final Vec3 hitVec;
        final Vec hullShotDirection;
        final Vec rayStart;
        final Vec hullImpactFallback;

        ShotTrace(Vec3 hitVec, Vec hullShotDirection, Vec rayStart, Vec hullImpactFallback) {
            this.hitVec = hitVec;
            this.hullShotDirection = hullShotDirection;
            this.rayStart = rayStart;
            this.hullImpactFallback = hullImpactFallback;
        }
    }

    record NearBox(ArmorBox box, double distance) {
    }

    record BoxQuery(ArmorHit hit, NearBox nearest) {
    }
}
