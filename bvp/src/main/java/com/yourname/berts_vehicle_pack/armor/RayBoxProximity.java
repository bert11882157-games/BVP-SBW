package com.yourname.berts_vehicle_pack.armor;

import java.util.Arrays;

/**
 * Pure closest-approach geometry between a ray segment and an origin-centred axis-aligned box.
 *
 * <p>Inputs are box-space values: the segment is {@code start + direction * t} for
 * {@code t in [0, length]} and the box spans {@code [-half, half]} on every axis. The squared
 * distance from the segment to the box is a convex piecewise quadratic in {@code t}; it is minimised
 * exactly on each interval between slab crossings, so the result is order independent and has no
 * iteration tolerance.</p>
 */
final class RayBoxProximity {
    private static final double AXIS_EPSILON = 1.0E-9D;
    private static final double FACE_NUDGE = 1.0D + 1.0E-9D;

    private RayBoxProximity() {
    }

    /** Closest segment parameter, or {@code NaN} when the inputs are degenerate. */
    static double closestParameter(double[] start, double[] direction, double length, double[] half) {
        if (!(length >= 0.0D) || !Double.isFinite(length) || !finite(start) || !finite(direction) || !finite(half)) {
            return Double.NaN;
        }
        double[] cuts = new double[8];
        int count = 0;
        cuts[count++] = 0.0D;
        cuts[count++] = length;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < AXIS_EPSILON) continue;
            for (double bound : new double[] {-half[axis], half[axis]}) {
                double t = (bound - start[axis]) / direction[axis];
                if (t > 0.0D && t < length) cuts[count++] = t;
            }
        }
        Arrays.sort(cuts, 0, count);
        double bestT = 0.0D;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index + 1 < count; index++) {
            double from = cuts[index];
            double to = cuts[index + 1];
            double mid = 0.5D * (from + to);
            double a = 0.0D;
            double b = 0.0D;
            for (int axis = 0; axis < 3; axis++) {
                double value = start[axis] + direction[axis] * mid;
                double offset;
                if (value > half[axis]) {
                    offset = start[axis] - half[axis];
                } else if (value < -half[axis]) {
                    offset = start[axis] + half[axis];
                } else {
                    continue;
                }
                a += direction[axis] * direction[axis];
                b += 2.0D * direction[axis] * offset;
            }
            double t = a > AXIS_EPSILON ? clamp(-b / (2.0D * a), from, to) : from;
            double distance = distanceSquared(start, direction, t, half);
            if (distance < bestDistance - 1.0E-12D) {
                bestDistance = distance;
                bestT = t;
            }
        }
        return bestT;
    }

    static double distanceSquared(double[] start, double[] direction, double t, double[] half) {
        double sum = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            double outside = Math.abs(start[axis] + direction[axis] * t) - half[axis];
            if (outside > 0.0D) sum += outside * outside;
        }
        return sum;
    }

    /**
     * Closest approach plus the face the shot enters when displaced onto the box.
     *
     * <p>The line is moved by the smallest offset that makes it pass through the box interior near
     * the closest point, then the entering slab is selected. The returned entry point lies on that
     * face (nudged a hair outward so a face-ratio normal lookup cannot tie on an edge). This gives
     * the incidence a shot would have against the plate instead of the always-grazing side face
     * that a near miss runs parallel to.</p>
     */
    static Approach approach(double[] start, double[] direction, double length, double[] half) {
        double t = closestParameter(start, direction, length, half);
        if (!Double.isFinite(t)) {
            return null;
        }
        double[] point = new double[3];
        double[] interior = new double[3];
        double gapSquared = 0.0D;
        for (int axis = 0; axis < 3; axis++) {
            point[axis] = start[axis] + direction[axis] * t;
            double clamped = clamp(point[axis], -half[axis], half[axis]);
            double outside = point[axis] - clamped;
            gapSquared += outside * outside;
            interior[axis] = Math.abs(point[axis]) >= half[axis]
                    ? Math.signum(point[axis]) * half[axis] * 0.5D
                    : point[axis];
        }
        double[] shiftedStart = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            shiftedStart[axis] = start[axis] + interior[axis] - point[axis];
        }
        int entryAxis = -1;
        double entryT = Double.NEGATIVE_INFINITY;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < AXIS_EPSILON) continue;
            double a = (-half[axis] - shiftedStart[axis]) / direction[axis];
            double b = (half[axis] - shiftedStart[axis]) / direction[axis];
            double near = Math.min(a, b);
            if (near > entryT) {
                entryT = near;
                entryAxis = axis;
            }
        }
        if (entryAxis < 0) {
            return null;
        }
        double[] entry = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            entry[axis] = shiftedStart[axis] + direction[axis] * entryT;
        }
        double faceSign = direction[entryAxis] > 0.0D ? -1.0D : 1.0D;
        entry[entryAxis] = faceSign * half[entryAxis] * FACE_NUDGE;
        return new Approach(t, Math.sqrt(gapSquared), entry, entryAxis, faceSign);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    private static boolean finite(double[] values) {
        if (values == null || values.length < 3) return false;
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(values[axis])) return false;
        }
        return true;
    }

    /** Box-space closest approach and entered face. */
    record Approach(double rayDistance, double gap, double[] entry, int entryAxis, double faceSign) {
    }
}
