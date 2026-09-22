package com.atsuishio.superbwarfare.world.phys;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget;
import com.atsuishio.superbwarfare.tools.OBB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/** Geometric hit ordering only; admission, damage and continuation remain with the caller. */
public final class ProjectileHitSelection {
    private ProjectileHitSelection() { }

    /** Retains the hit and its part together. Exact distance ties retain candidate order. */
    public static final class Nearest<T> {
        private final Vec3 start;
        private double distanceSquared;
        private @Nullable T target;
        private @Nullable Vec3 point;
        private @Nullable OBB.Part part;

        public Nearest(Vec3 start, double maximumDistanceSquared) {
            this.start = start;
            this.distanceSquared = maximumDistanceSquared;
        }

        public void consider(T candidate, @Nullable Vec3 hitPoint, @Nullable OBB.Part hitPart) {
            if (hitPoint == null) return;
            double distance = start.distanceToSqr(hitPoint);
            if (!Double.isFinite(distance) || distance > distanceSquared
                    || !(distanceSquared >= 0.0D) || (target != null && distance >= distanceSquared)) return;
            target = candidate;
            point = hitPoint;
            part = hitPart;
            distanceSquared = distance;
        }

        public @Nullable T target() { return target; }
        public @Nullable Vec3 point() { return point; }
        public @Nullable OBB.Part part() { return part; }
    }

    /** Uses the existing closed-segment OBB clip, including start-inside and endpoint contacts. */
    public static @Nullable ProjectileCollisionTarget.Hit nearestObb(
            Iterable<OBB> boxes, Vec3 start, Vec3 end, double inflation) {
        var nearest = new Nearest<OBB>(start, Double.MAX_VALUE);
        var from = OBB.vec3ToVector3d(start);
        var to = OBB.vec3ToVector3d(end);
        for (OBB original : boxes) {
            OBB box = inflation == 0.0D ? original : original.inflate(inflation);
            var clipped = box.clip(from, to);
            if (clipped.isPresent()) {
                nearest.consider(box, OBB.vector3dToVec3(clipped.get()), box.part);
            }
        }
        return nearest.target() == null ? null : new ProjectileCollisionTarget.Hit(nearest.point(), nearest.part());
    }

    /** Current sweep origin, never the moving shooter's position or the original launch point. */
    public static <T> void sortFromStart(List<T> hits, Vec3 start, Function<T, Vec3> point) {
        hits.sort(Comparator.comparingDouble(hit -> start.distanceToSqr(point.apply(hit))));
    }
}
