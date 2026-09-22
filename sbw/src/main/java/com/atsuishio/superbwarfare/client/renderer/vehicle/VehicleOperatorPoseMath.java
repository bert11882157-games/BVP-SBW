package com.atsuishio.superbwarfare.client.renderer.vehicle;

/** Bounded standing upper-body fit in vanilla humanoid model pixels; legs never move. */
public final class VehicleOperatorPoseMath {
    private static final double PALM_Y = 9.0;
    private static final double PALM_TOLERANCE = 0.5;
    private static final int REFINEMENT_STEPS = 10;

    private VehicleOperatorPoseMath() {
    }

    /** Reused by one model instance. A failed solve does not publish a pose. */
    public static final class Result {
        public float leanRadians;
        public float bodyY;
        public float bodyZ;
        public float shoulderY;
        public float shoulderZ;
        public float leftXRot;
        public float leftYRot;
        public float leftZRot;
        public float rightXRot;
        public float rightYRot;
        public float rightZRot;
        public double maximumPalmError;
        public int evaluations;
    }

    public static boolean solve(double leftX, double leftY, double leftZ,
                                double rightX, double rightY, double rightZ,
                                double palmOffsetX, double maxForwardDegrees,
                                double maxBackwardDegrees, Result result) {
        if (result == null || !finite(leftX, leftY, leftZ, rightX, rightY, rightZ)
                || (palmOffsetX != 0.5 && palmOffsetX != 1.0)
                || !Double.isFinite(maxForwardDegrees) || maxForwardDegrees < 0
                || maxForwardDegrees > 75 || !Double.isFinite(maxBackwardDegrees)
                || maxBackwardDegrees < 0 || maxBackwardDegrees > 30) {
            return false;
        }
        result.evaluations = 0;
        double reach = Math.hypot(PALM_Y, palmOffsetX);
        double shoulderHeight = 9 + palmOffsetX;
        double lean = Double.NaN;
        if (error(leftX, leftY, leftZ, rightX, rightY, rightZ, 0, reach, shoulderHeight, result)
                <= PALM_TOLERANCE) {
            lean = 0;
        }
        // Prefer the feasible pose closest to upright, not a distant second solution.
        for (int magnitude = 1; Double.isNaN(lean) && magnitude <= 75; magnitude++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                double bound = sign > 0 ? maxForwardDegrees : maxBackwardDegrees;
                if (magnitude - 1 >= bound) continue;
                double high = Math.min(magnitude, bound);
                if (error(leftX, leftY, leftZ, rightX, rightY, rightZ,
                        high * sign, reach, shoulderHeight, result) > PALM_TOLERANCE) continue;
                double low = magnitude - 1;
                for (int step = 0; step < REFINEMENT_STEPS; step++) {
                    double middle = (low + high) * 0.5;
                    if (error(leftX, leftY, leftZ, rightX, rightY, rightZ,
                            middle * sign, reach, shoulderHeight, result) <= PALM_TOLERANCE) high = middle;
                    else low = middle;
                }
                lean = high * sign;
                break;
            }
        }
        if (!Double.isFinite(lean)) return false;
        double radians = Math.toRadians(lean);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        result.leanRadians = (float) radians;
        result.bodyY = (float) (12 - 12 * cosine);
        result.bodyZ = (float) (-12 * sine);
        result.shoulderY = (float) (12 - shoulderHeight * cosine);
        result.shoulderZ = (float) (-shoulderHeight * sine);
        arm(leftX - 5, leftY - result.shoulderY, leftZ - result.shoulderZ,
                palmOffsetX, true, result);
        arm(rightX + 5, rightY - result.shoulderY, rightZ - result.shoulderZ,
                -palmOffsetX, false, result);
        result.maximumPalmError = error(leftX, leftY, leftZ, rightX, rightY, rightZ,
                lean, reach, shoulderHeight, result);
        return true;
    }

    private static double error(double lx, double ly, double lz, double rx, double ry,
                                double rz, double lean, double reach, double shoulderHeight,
                                Result result) {
        result.evaluations++;
        double radians = Math.toRadians(lean);
        double y = 12 - shoulderHeight * Math.cos(radians);
        double z = -shoulderHeight * Math.sin(radians);
        return Math.max(Math.abs(length(lx - 5, ly - y, lz - z) - reach),
                Math.abs(length(rx + 5, ry - y, rz - z) - reach));
    }

    private static void arm(double x, double y, double z, double palmX,
                            boolean left, Result result) {
        double distance = length(x, y, z);
        // The shortest rotation from the real palm center to the target direction.
        double reach = Math.hypot(palmX, PALM_Y);
        double denominator = reach * distance;
        double qx = PALM_Y * z / denominator;
        double qy = -palmX * z / denominator;
        double qz = (palmX * y - PALM_Y * x) / denominator;
        double qw = 1 + (palmX * x + PALM_Y * y) / denominator;
        if (qw < 1.0e-10) {
            qx = PALM_Y / reach;
            qy = -palmX / reach;
            qz = 0;
            qw = 0;
        }
        double inverse = 1 / Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        qx *= inverse;
        qy *= inverse;
        qz *= inverse;
        qw *= inverse;
        // ModelPart's rotateZYX composes Rz * Ry * Rx.
        float xRot = (float) Math.atan2(2 * (qw * qx + qy * qz),
                1 - 2 * (qx * qx + qy * qy));
        float yRot = (float) Math.asin(Math.max(-1, Math.min(1,
                2 * (qw * qy - qz * qx))));
        float zRot = (float) Math.atan2(2 * (qw * qz + qx * qy),
                1 - 2 * (qy * qy + qz * qz));
        if (left) {
            result.leftXRot = xRot;
            result.leftYRot = yRot;
            result.leftZRot = zRot;
        } else {
            result.rightXRot = xRot;
            result.rightYRot = yRot;
            result.rightZRot = zRot;
        }
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static boolean finite(double a, double b, double c,
                                  double d, double e, double f) {
        return Double.isFinite(a) && Double.isFinite(b) && Double.isFinite(c)
                && Double.isFinite(d) && Double.isFinite(e) && Double.isFinite(f);
    }
}
