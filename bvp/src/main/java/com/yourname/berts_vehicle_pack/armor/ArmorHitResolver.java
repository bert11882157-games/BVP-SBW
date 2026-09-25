package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Armor volume queries shared by every armor consumer. Volumes may be boxes or meshes
 * ({@link ArmorVolume}); each query transforms the shot into the hull, turret and barrel frames once,
 * culls volumes by their bounds and only then runs the exact test. Culling never changes a result:
 * the bounds grown by {@link ArmorVolume#skinPad} contain the grown volume.
 */
final class ArmorHitResolver {
    static final double ARMOR_RAY_BACKTRACE_BLOCKS = 4.0D;
    static final double ARMOR_RAY_DISTANCE_BLOCKS = 12.0D;
    /** Rounding margin for culling comparisons; far below any gameplay distance. */
    private static final double CULL_MARGIN = 1.0E-9D;

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
        FramePoints points = new FramePoints(target, hullImpact);
        for (ArmorBox box : boxes) {
            Vec frameImpact = points.point(box);
            if (frameImpact == null) continue;
            if (cannotBeWithin(box, frameImpact, Math.min(impactTolerance, bestDistance))) continue;
            double distance = box.distanceOutside(frameImpact);
            if (distance <= impactTolerance && distance < bestDistance) {
                best = ArmorHit.proximity(box, frameImpact, hullImpact, distance);
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
        FramePoints points = new FramePoints(target, trace.hullImpactFallback);
        for (ArmorBox box : boxes) {
            Vec frameImpact = points.point(box);
            if (frameImpact == null) continue;
            // A box farther than the nearest so far can be neither the nearest nor the fallback.
            if (nearest != null && cannotBeWithin(box, frameImpact, nearest.distance())) continue;
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
                : ArmorHit.proximity(fallbackBox, fallbackFrame, trace.hullImpactFallback, fallbackDistance);
        return new BoxQuery(fallbackHit, nearest);
    }

    /** ERA contact is selected only around the accepted point, never on an earlier ray crossing. */
    static BoxQuery findNearestBoxAtImpact(ArmorTarget target, List<ArmorBox> boxes, Vec hullImpact,
                                           double impactTolerance) {
        ArmorHit hit = null;
        double bestDistance = Double.MAX_VALUE;
        NearBox nearest = null;
        FramePoints points = new FramePoints(target, hullImpact);
        for (ArmorBox box : boxes) {
            Vec frameImpact = points.point(box);
            if (frameImpact == null) continue;
            if (nearest != null && cannotBeWithin(box, frameImpact, nearest.distance())) continue;
            double distanceOutside = box.distanceOutside(frameImpact);
            if (nearest == null || distanceOutside < nearest.distance) {
                nearest = new NearBox(box, distanceOutside);
            }
            if (distanceOutside <= impactTolerance && distanceOutside < bestDistance) {
                hit = ArmorHit.proximity(box, frameImpact, hullImpact, distanceOutside);
                bestDistance = distanceOutside;
            }
        }
        return new BoxQuery(hit, nearest);
    }

    /**
     * Nearest volume to the shot segment {@code hullStart + hullDirection * [0, length]}: the gap
     * (0 when the segment touches it), the segment parameter and a surface point. Null when every
     * volume is further than {@code maxGap} or there are none. Used for the "Shot missed!" report.
     */
    static RaySnap findNearestBoxToRay(ArmorTarget target, List<ArmorBox> boxes, Vec hullStart,
                                       Vec hullDirection, double length, double maxGap) {
        Vec direction = hullDirection.normalize();
        if (direction.length() < 1.0E-6D || boxes.isEmpty()) {
            return null;
        }
        FrameRays rays = new FrameRays(target, hullStart, direction);
        ArmorBox bestBox = null;
        ArmorProfiles.SegmentApproach best = null;
        for (ArmorBox box : boxes) {
            int frame = FrameRays.frameIndex(box);
            Vec frameStart = rays.start(frame);
            if (frameStart == null) continue;
            Vec frameDirection = rays.direction(frame);
            ArmorProfiles.SegmentApproach approach = box.closestApproach(frameStart, frameDirection, length);
            if (approach == null || !Double.isFinite(approach.gap())) continue;
            if (best == null || approach.gap() < best.gap() - 1.0E-6D
                    || (approach.gap() <= best.gap() + 1.0E-6D && approach.rayDistance() < best.rayDistance())) {
                best = approach;
                bestBox = box;
            }
        }
        if (best == null || best.gap() > maxGap) {
            return null;
        }
        Vec hullImpact = pointToHullFrame(target, bestBox, best.frameEntry());
        if (hullImpact == null) {
            return null;
        }
        return new RaySnap(new ArmorHit(bestBox, best.frameEntry(), hullImpact, best.rayDistance()), best.gap());
    }

    static ArmorHit findFirstBoxOnRay(ArmorTarget target, List<ArmorBox> boxes,
                                      Vec hullStart, Vec hullDirection, double maxDistance,
                                      double impactTolerance) {
        Vec direction = hullDirection.normalize();
        if (direction.length() < 1.0E-6D) {
            return null;
        }
        double inflation = Math.min(0.03D, Math.max(0.005D, impactTolerance * 0.1D));
        FrameRays rays = new FrameRays(target, hullStart, direction);
        ArmorBox bestBox = null;
        int bestFrame = 0;
        double best = Double.POSITIVE_INFINITY;
        for (ArmorBox box : boxes) {
            int frame = FrameRays.frameIndex(box);
            Vec frameStart = rays.start(frame);
            if (frameStart == null) continue;
            Vec frameDirection = rays.direction(frame);
            double boundsEntry = box.boundsEntry(frameStart, frameDirection, maxDistance, inflation);
            // Missing the grown bounds, or entering them beyond the best hit, rules the volume out.
            if (!(boundsEntry <= best + CULL_MARGIN)) continue;
            double distance = box.rayHitDistance(frameStart, frameDirection, maxDistance, inflation);
            if (Double.isFinite(distance) && distance >= 0.0D && (bestBox == null || distance < best)) {
                bestBox = box;
                bestFrame = frame;
                best = distance;
            }
        }
        if (bestBox == null) {
            return null;
        }
        Vec frameStart = rays.start(bestFrame);
        Vec frameDirection = rays.direction(bestFrame);
        Vec frameImpact = frameStart.add(frameDirection.scale(best));
        Vec hullImpact = pointToHullFrame(target, bestBox, frameImpact);
        // Mesh hits carry the true normal of the entered triangle; box hits keep the legacy lookup.
        Vec normal = bestBox.isMesh()
                ? bestBox.volume.rayEntryNormal(frameStart, frameDirection, maxDistance, inflation)
                : null;
        return new ArmorHit(bestBox, frameImpact, hullImpact, best, normal);
    }

    private static boolean cannotBeWithin(ArmorBox box, Vec framePoint, double limit) {
        if (!(limit < Double.MAX_VALUE)) return false;
        double reach = limit + CULL_MARGIN;
        return box.boundsDistanceSquared(framePoint) > reach * reach;
    }

    static Vec directionToBoxFrame(ArmorTarget target, ArmorBox box, Vec hullDirection) {
        return directionToFrame(target, FrameRays.frameIndex(box), hullDirection);
    }

    static Vec pointToBoxFrame(ArmorTarget target, ArmorBox box, Vec hullPoint) {
        return pointToFrame(target, FrameRays.frameIndex(box), hullPoint);
    }

    /** Frame 0 = hull, 1 = turret, 2 = barrel. */
    private static Vec directionToFrame(ArmorTarget target, int frame, Vec hullDirection) {
        if (frame == 2) {
            ArmorCoordinateFrame.BarrelFrame barrel = target.barrelFrame();
            return barrel == null ? Vec.ZERO : barrel.toBarrelDirection(hullDirection);
        }
        return frame == 1 ? hullDirection.rotateY(-target.turretFrameYaw()) : hullDirection;
    }

    private static Vec pointToFrame(ArmorTarget target, int frame, Vec hullPoint) {
        if (frame == 2) {
            ArmorCoordinateFrame.BarrelFrame barrel = target.barrelFrame();
            return barrel == null ? null : barrel.toBarrelPoint(hullPoint);
        }
        if (frame == 0) {
            return hullPoint;
        }
        Vec pivot = target.turretPivot();
        return pivot.add(hullPoint.subtract(pivot).rotateY(-target.turretFrameYaw()));
    }

    static Vec pointToHullFrame(ArmorTarget target, ArmorBox box, Vec framePoint) {
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

    /** Frame-local normal rotated back into hull (armor-local) coordinates; null when unavailable. */
    static Vec normalToHullFrame(ArmorTarget target, ArmorBox box, Vec frameNormal) {
        if (frameNormal == null) return null;
        if (box.isBarrelFrame()) {
            ArmorCoordinateFrame.BarrelFrame frame = target.barrelFrame();
            return frame == null ? null : frame.toHullDirection(frameNormal);
        }
        return box.isTurretFrame() ? frameNormal.rotateY(target.turretFrameYaw()) : frameNormal;
    }

    static Vec3 shotDirection(Projectile projectile, Vec3 hitVec) {
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

    /** One shot ray expressed in the hull, turret and barrel frames, each computed at most once. */
    private static final class FrameRays {
        private final ArmorTarget target;
        private final Vec hullStart;
        private final Vec hullDirection;
        private final Vec[] starts = new Vec[3];
        private final Vec[] directions = new Vec[3];
        private final boolean[] resolved = new boolean[3];

        FrameRays(ArmorTarget target, Vec hullStart, Vec hullDirection) {
            this.target = target;
            this.hullStart = hullStart;
            this.hullDirection = hullDirection;
        }

        static int frameIndex(ArmorBox box) {
            return box.isBarrelFrame() ? 2 : box.isTurretFrame() ? 1 : 0;
        }

        Vec start(int frame) {
            resolve(frame);
            return starts[frame];
        }

        Vec direction(int frame) {
            resolve(frame);
            return directions[frame];
        }

        private void resolve(int frame) {
            if (resolved[frame]) return;
            resolved[frame] = true;
            starts[frame] = pointToFrame(target, frame, hullStart);
            directions[frame] = directionToFrame(target, frame, hullDirection).normalize();
        }
    }

    /** One impact point expressed in the hull, turret and barrel frames, each computed at most once. */
    private static final class FramePoints {
        private final ArmorTarget target;
        private final Vec hullPoint;
        private final Vec[] points = new Vec[3];
        private final boolean[] resolved = new boolean[3];

        FramePoints(ArmorTarget target, Vec hullPoint) {
            this.target = target;
            this.hullPoint = hullPoint;
        }

        Vec point(ArmorBox box) {
            int frame = FrameRays.frameIndex(box);
            if (!resolved[frame]) {
                resolved[frame] = true;
                points[frame] = pointToFrame(target, frame, hullPoint);
            }
            return points[frame];
        }
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

    /** The volume the shot passed closest to, with the gap and the nearest surface point. */
    record RaySnap(ArmorHit hit, double gap) {
    }
}
