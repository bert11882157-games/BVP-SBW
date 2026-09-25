package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.SegmentApproach;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

/**
 * An oriented box: center, half extents and a rotation applied X, then Y, then Z (degrees).
 *
 * <p>This is the original box armor geometry, moved here unchanged so the box path keeps its exact
 * numerics. {@link #normalAt} keeps the legacy face-ratio rule that every box consumer used.</p>
 */
public final class ArmorBoxVolume implements ArmorVolume {
    private static final double RAY_AXIS_EPSILON = 1.0E-7D;
    private static final int[][] FACES = {
            {0, 2, 6, 4}, {1, 3, 7, 5},   // -x, +x
            {0, 1, 5, 4}, {2, 3, 7, 6},   // -y, +y
            {0, 1, 3, 2}, {4, 5, 7, 6}};  // -z, +z
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    private final Vec center;
    private final Vec halfSize;
    private final Vec rotationDeg;
    private final double[] corners;
    private final double[] bounds;

    ArmorBoxVolume(Vec center, Vec halfSize, Vec rotationDeg) {
        this.center = center;
        this.halfSize = halfSize;
        this.rotationDeg = rotationDeg;
        this.corners = new double[24];
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            Vec point = rotate(new Vec((corner & 1) == 0 ? -halfSize.x : halfSize.x,
                    (corner & 2) == 0 ? -halfSize.y : halfSize.y,
                    (corner & 4) == 0 ? -halfSize.z : halfSize.z)).add(center);
            corners[corner * 3] = point.x;
            corners[corner * 3 + 1] = point.y;
            corners[corner * 3 + 2] = point.z;
            b[0] = Math.min(b[0], point.x);
            b[1] = Math.min(b[1], point.y);
            b[2] = Math.min(b[2], point.z);
            b[3] = Math.max(b[3], point.x);
            b[4] = Math.max(b[4], point.y);
            b[5] = Math.max(b[5], point.z);
        }
        this.bounds = b;
    }

    public Vec center() {
        return center;
    }

    public Vec halfSize() {
        return halfSize;
    }

    public Vec rotationDeg() {
        return rotationDeg;
    }

    @Override
    public boolean isMesh() {
        return false;
    }

    @Override
    public Vec normalAt(Vec localImpact) {
        Vec boxPoint = toBoxSpace(localImpact);
        double nx = Math.abs(boxPoint.x / this.halfSize.x);
        double ny = Math.abs(boxPoint.y / this.halfSize.y);
        double nz = Math.abs(boxPoint.z / this.halfSize.z);
        Vec normal;
        if (nx >= ny && nx >= nz) {
            normal = new Vec(Math.signum(boxPoint.x), 0.0D, 0.0D);
        } else if (ny >= nz) {
            normal = new Vec(0.0D, Math.signum(boxPoint.y), 0.0D);
        } else {
            normal = new Vec(0.0D, 0.0D, Math.signum(boxPoint.z));
        }
        return rotate(normal).normalize();
    }

    @Override
    public double minY() {
        Vec axisX = rotate(new Vec(this.halfSize.x, 0.0D, 0.0D));
        Vec axisY = rotate(new Vec(0.0D, this.halfSize.y, 0.0D));
        Vec axisZ = rotate(new Vec(0.0D, 0.0D, this.halfSize.z));
        return this.center.y - (Math.abs(axisX.y) + Math.abs(axisY.y) + Math.abs(axisZ.y));
    }

    @Override
    public SegmentApproach closestApproach(Vec frameStart, Vec frameDirection, double length) {
        Vec start = toBoxSpace(frameStart);
        Vec direction = inverseRotate(frameDirection).normalize();
        if (direction.length() < 1.0E-6D) {
            return null;
        }
        RayBoxProximity.Approach approach = RayBoxProximity.approach(
                new double[] {start.x, start.y, start.z},
                new double[] {direction.x, direction.y, direction.z},
                length,
                new double[] {this.halfSize.x, this.halfSize.y, this.halfSize.z});
        if (approach == null) {
            return null;
        }
        double[] entry = approach.entry();
        Vec frameEntry = rotate(new Vec(entry[0], entry[1], entry[2])).add(this.center);
        return new SegmentApproach(approach.rayDistance(), approach.gap(), frameEntry);
    }

    @Override
    public boolean contains(Vec point) {
        Vec boxPoint = toBoxSpace(point);
        return Math.abs(boxPoint.x) <= this.halfSize.x
                && Math.abs(boxPoint.y) <= this.halfSize.y
                && Math.abs(boxPoint.z) <= this.halfSize.z;
    }

    @Override
    public double distanceOutside(Vec localPoint) {
        Vec boxPoint = toBoxSpace(localPoint);
        double dx = Math.max(0.0D, Math.abs(boxPoint.x) - this.halfSize.x);
        double dy = Math.max(0.0D, Math.abs(boxPoint.y) - this.halfSize.y);
        double dz = Math.max(0.0D, Math.abs(boxPoint.z) - this.halfSize.z);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public double rayHitDistance(Vec localStart, Vec localDirection, double maxDistance, double inflation) {
        Vec start = toBoxSpace(localStart);
        Vec direction = inverseRotate(localDirection).normalize();
        double tMin = 0.0D;
        double tMax = maxDistance;

        double half = this.halfSize.x + inflation;
        if (Math.abs(direction.x) < RAY_AXIS_EPSILON) {
            if (start.x < -half || start.x > half) {
                return Double.NaN;
            }
        } else {
            double a = (-half - start.x) / direction.x;
            double b = (half - start.x) / direction.x;
            double near = Math.min(a, b);
            double far = Math.max(a, b);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) {
                return Double.NaN;
            }
        }

        half = this.halfSize.y + inflation;
        if (Math.abs(direction.y) < RAY_AXIS_EPSILON) {
            if (start.y < -half || start.y > half) {
                return Double.NaN;
            }
        } else {
            double a = (-half - start.y) / direction.y;
            double b = (half - start.y) / direction.y;
            double near = Math.min(a, b);
            double far = Math.max(a, b);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) {
                return Double.NaN;
            }
        }

        half = this.halfSize.z + inflation;
        if (Math.abs(direction.z) < RAY_AXIS_EPSILON) {
            if (start.z < -half || start.z > half) {
                return Double.NaN;
            }
        } else {
            double a = (-half - start.z) / direction.z;
            double b = (half - start.z) / direction.z;
            double near = Math.min(a, b);
            double far = Math.max(a, b);
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) {
                return Double.NaN;
            }
        }

        if (tMax < 0.0D || tMin > maxDistance) {
            return Double.NaN;
        }
        return Math.max(0.0D, tMin);
    }

    /**
     * The slab face the ray enters (the latest entering slab, which may lie behind the start when
     * the start is inside). Consumers of box volumes keep using {@link #normalAt}; this exists so
     * the mesh path can be compared against the true entered face.
     */
    @Override
    public Vec rayEntryNormal(Vec localStart, Vec localDirection, double maxDistance, double inflation) {
        if (!Double.isFinite(rayHitDistance(localStart, localDirection, maxDistance, inflation))) {
            return null;
        }
        Vec start = toBoxSpace(localStart);
        Vec direction = inverseRotate(localDirection).normalize();
        double[] s = {start.x, start.y, start.z};
        double[] d = {direction.x, direction.y, direction.z};
        double[] h = {halfSize.x + inflation, halfSize.y + inflation, halfSize.z + inflation};
        int axis = -1;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < RAY_AXIS_EPSILON) continue;
            double near = Math.min((-h[i] - s[i]) / d[i], (h[i] - s[i]) / d[i]);
            if (near > best) {
                best = near;
                axis = i;
            }
        }
        if (axis < 0) {
            return normalAt(localStart);
        }
        double sign = d[axis] > 0.0D ? -1.0D : 1.0D;
        Vec normal = new Vec(axis == 0 ? sign : 0.0D, axis == 1 ? sign : 0.0D, axis == 2 ? sign : 0.0D);
        return rotate(normal).normalize();
    }

    @Override
    public Vec centroid() {
        return center;
    }

    @Override
    public double volume() {
        return 8.0D * halfSize.x * halfSize.y * halfSize.z;
    }

    @Override
    public double[] bounds() {
        return bounds.clone();
    }

    @Override
    public double skinPad(double inflation) {
        // Growing every half extent by the skin moves a corner by at most sqrt(3) times the skin.
        return Math.max(0.0D, inflation) * 1.7320508075688772D + 1.0E-9D;
    }

    @Override
    public int vertexCount() {
        return 8;
    }

    @Override
    public void vertex(int index, double[] out) {
        out[0] = corners[index * 3];
        out[1] = corners[index * 3 + 1];
        out[2] = corners[index * 3 + 2];
    }

    @Override
    public void forEachTriangle(TriangleSink sink) {
        for (int face = 0; face < 6; face++) {
            int[] quad = orientedFace(face);
            emit(sink, quad[0], quad[1], quad[2]);
            emit(sink, quad[0], quad[2], quad[3]);
        }
    }

    @Override
    public void forEachFeatureEdge(EdgeSink sink) {
        for (int[] edge : EDGES) {
            int a = edge[0] * 3;
            int b = edge[1] * 3;
            sink.accept(corners[a], corners[a + 1], corners[a + 2], corners[b], corners[b + 1], corners[b + 2]);
        }
    }

    @Override
    public Vec dominantFaceNormal(Vec hint) {
        double[] areas = {halfSize.y * halfSize.z, halfSize.x * halfSize.z, halfSize.x * halfSize.y};
        Vec best = null;
        double bestArea = -1.0D;
        double bestDot = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            for (double sign : new double[] {-1.0D, 1.0D}) {
                Vec normal = rotate(new Vec(axis == 0 ? sign : 0.0D, axis == 1 ? sign : 0.0D,
                        axis == 2 ? sign : 0.0D)).normalize();
                double dot = normal.dot(hint);
                if (dot <= 1.0E-9D) continue;
                double area = areas[axis];
                if (area > bestArea * (1.0D + 1.0E-9D)
                        || (Math.abs(area - bestArea) <= bestArea * 1.0E-9D && dot > bestDot)) {
                    best = normal;
                    bestArea = area;
                    bestDot = dot;
                }
            }
        }
        return best;
    }

    private int[] orientedFace(int face) {
        int[] quad = FACES[face].clone();
        int axis = face / 2;
        double sign = (face & 1) == 0 ? -1.0D : 1.0D;
        Vec outward = rotate(new Vec(axis == 0 ? sign : 0.0D, axis == 1 ? sign : 0.0D, axis == 2 ? sign : 0.0D));
        double[] a = point(quad[0]);
        double[] b = point(quad[1]);
        double[] c = point(quad[2]);
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        if (nx * outward.x + ny * outward.y + nz * outward.z < 0.0D) {
            int swap = quad[1];
            quad[1] = quad[3];
            quad[3] = swap;
        }
        return quad;
    }

    private double[] point(int corner) {
        return new double[] {corners[corner * 3], corners[corner * 3 + 1], corners[corner * 3 + 2]};
    }

    private void emit(TriangleSink sink, int a, int b, int c) {
        sink.accept(corners[a * 3], corners[a * 3 + 1], corners[a * 3 + 2],
                corners[b * 3], corners[b * 3 + 1], corners[b * 3 + 2],
                corners[c * 3], corners[c * 3 + 1], corners[c * 3 + 2]);
    }

    private Vec toBoxSpace(Vec localPoint) {
        return inverseRotate(localPoint.subtract(this.center));
    }

    private Vec inverseRotate(Vec value) {
        Vec result = value.rotateZ(-this.rotationDeg.z);
        result = result.rotateY(-this.rotationDeg.y);
        return result.rotateX(-this.rotationDeg.x);
    }

    private Vec rotate(Vec value) {
        Vec result = value.rotateX(this.rotationDeg.x);
        result = result.rotateY(this.rotationDeg.y);
        return result.rotateZ(this.rotationDeg.z);
    }
}
