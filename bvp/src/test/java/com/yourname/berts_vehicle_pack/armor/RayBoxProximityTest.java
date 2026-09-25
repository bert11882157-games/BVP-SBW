package com.yourname.berts_vehicle_pack.armor;

import java.util.Random;

/** Headless checks for the nearest-plate geometry used by strict armor profiles. */
public final class RayBoxProximityTest {
    private static final double[] UNIT = {1.0D, 1.0D, 1.0D};

    public static void main(String[] args) {
        intersectingSegmentHasZeroGapAtEntry();
        passUnderBoxEntersSideFace();
        skewPassPastEdgeKeepsEarliestClosestPoint();
        segmentEndingShortClampsToEnd();
        verticalShotBesideThinPlateEntersTopFace();
        slopedApproachEntersThinFace();
        degenerateInputsAreRejected();
        matchesDenseSampling();
        System.out.println("PASS ray-box closest approach, gap, entered face and sampling agreement");
    }

    private static void intersectingSegmentHasZeroGapAtEntry() {
        var approach = RayBoxProximity.approach(v(-5, 0.2, 0.1), v(1, 0, 0), 12, UNIT);
        check(approach != null, "intersecting approach exists");
        near(approach.gap(), 0.0D, "intersecting gap");
        near(approach.rayDistance(), 4.0D, "intersecting segment picks earliest contact");
        check(approach.entryAxis() == 0 && approach.faceSign() < 0, "intersecting ray enters -x face");
    }

    private static void passUnderBoxEntersSideFace() {
        // A side shot passing 0.5 below a plate: the nearest face is the grazing bottom face, but
        // the resolved incidence must use the side face the shot would strike.
        var approach = RayBoxProximity.approach(v(-5, -1.5, 0.3), v(1, 0, 0), 12, UNIT);
        near(approach.gap(), 0.5D, "under-pass gap");
        near(approach.rayDistance(), 4.0D, "under-pass closest point is the first overlapping point");
        check(approach.entryAxis() == 0 && approach.faceSign() < 0, "under-pass enters -x side face");
        check(approach.entry()[0] < -1.0D, "entry nudged outside the entered face");
        check(Math.abs(approach.entry()[1]) < 1.0D && Math.abs(approach.entry()[2]) < 1.0D,
                "entry point lies inside the face");
    }

    private static void skewPassPastEdgeKeepsEarliestClosestPoint() {
        var approach = RayBoxProximity.approach(v(2, 2, -5), v(0, 0, 1), 12, UNIT);
        near(approach.gap(), Math.sqrt(2.0D), "edge pass gap");
        near(approach.rayDistance(), 4.0D, "edge pass earliest closest parameter");
        check(approach.entryAxis() == 2 && approach.faceSign() < 0, "edge pass enters -z face");
    }

    private static void segmentEndingShortClampsToEnd() {
        var approach = RayBoxProximity.approach(v(-10, 0, 0), v(1, 0, 0), 5, UNIT);
        near(approach.rayDistance(), 5.0D, "short segment clamps to its end");
        near(approach.gap(), 4.0D, "short segment gap");
    }

    private static void verticalShotBesideThinPlateEntersTopFace() {
        double[] half = {0.5D, 0.3D, 0.01D};
        var approach = RayBoxProximity.approach(v(0, 5, 0.2), v(0, -1, 0), 12, half);
        near(approach.gap(), 0.19D, "vertical pass gap beside thin plate");
        check(approach.entryAxis() == 1 && approach.faceSign() > 0, "vertical pass enters top face");
    }

    private static void slopedApproachEntersThinFace() {
        // Thin plate (z), shot travelling +z and slightly down, passing below it.
        double[] half = {1.2D, 0.27D, 0.05D};
        double[] direction = normalize(v(0, -0.3, 1));
        var approach = RayBoxProximity.approach(v(0, -0.2, -4), direction, 12, half);
        check(approach.gap() > 0.0D, "sloped pass misses");
        check(approach.entryAxis() == 2 && approach.faceSign() < 0, "sloped pass enters thin front face");
    }

    private static void degenerateInputsAreRejected() {
        check(RayBoxProximity.approach(v(0, 0, 0), v(0, 0, 0), 12, UNIT) == null, "zero direction rejected");
        check(Double.isNaN(RayBoxProximity.closestParameter(v(0, 0, 0), v(1, 0, 0), Double.NaN, UNIT)),
                "NaN length rejected");
        check(Double.isNaN(RayBoxProximity.closestParameter(v(Double.NaN, 0, 0), v(1, 0, 0), 1, UNIT)),
                "NaN start rejected");
    }

    private static void matchesDenseSampling() {
        Random random = new Random(0x5EEDL);
        for (int trial = 0; trial < 2000; trial++) {
            double[] half = {0.02D + random.nextDouble() * 2, 0.02D + random.nextDouble() * 2,
                    0.02D + random.nextDouble() * 2};
            double[] start = {random.nextGaussian() * 4, random.nextGaussian() * 4, random.nextGaussian() * 4};
            double[] direction = normalize(v(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()));
            double length = random.nextDouble() * 12;
            double t = RayBoxProximity.closestParameter(start, direction, length, half);
            check(t >= 0.0D && t <= length, "parameter inside segment");
            double analytic = RayBoxProximity.distanceSquared(start, direction, t, half);
            double sampled = Double.POSITIVE_INFINITY;
            for (int step = 0; step <= 4000; step++) {
                sampled = Math.min(sampled, RayBoxProximity.distanceSquared(
                        start, direction, length * step / 4000.0D, half));
            }
            check(analytic <= sampled + 1.0E-9D, "analytic minimum is not worse than sampling");
            check(Math.sqrt(analytic) >= Math.sqrt(sampled) - length / 4000.0D - 1.0E-9D,
                    "analytic minimum agrees with sampling");
            var approach = RayBoxProximity.approach(start, direction, length, half);
            check(approach != null, "approach exists");
            near(approach.gap(), Math.sqrt(analytic), "approach gap equals closest distance");
            double[] entry = approach.entry();
            int axis = approach.entryAxis();
            check(Math.abs(entry[axis]) > half[axis], "entry nudged beyond its face");
            check(direction[axis] * approach.faceSign() < 0.0D, "entered face opposes the shot");
            for (int other = 0; other < 3; other++) {
                if (other != axis) {
                    check(Math.abs(entry[other]) <= half[other] + 1.0E-9D, "entry lies on the face");
                }
            }
        }
    }

    private static double[] v(double x, double y, double z) {
        return new double[] {x, y, z};
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(value[0] * value[0] + value[1] * value[1] + value[2] * value[2]);
        return length == 0.0D ? value : v(value[0] / length, value[1] / length, value[2] / length);
    }

    private static void near(double actual, double expected, String label) {
        if (!(Math.abs(actual - expected) <= 1.0E-6D)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
