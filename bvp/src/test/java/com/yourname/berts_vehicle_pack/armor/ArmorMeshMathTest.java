package com.yourname.berts_vehicle_pack.armor;

import java.util.Random;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.check;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.near;

/** Headless checks for the triangle primitives behind mesh armor volumes. */
public final class ArmorMeshMathTest {
    private static final double[] TRI = {0, 0, 0, 2, 0, 0, 0, 2, 0}; // z = 0 plane, right triangle

    public static void main(String[] args) {
        rayTriangleHitsBothSides();
        rayTriangleMissesAndParallel();
        rayTriangleSharedEdgeIsWatertight();
        offsetTriangleShiftsHit();
        capsuleAndSphere();
        closestPointMatchesSampling();
        segmentTriangleMatchesSampling();
        solidAngleOfClosedBox();
        rayAabbSlab();
        System.out.println("PASS mesh math: ray/triangle, capsule, closest points, segment distance, solid angle, slabs");
    }

    private static void rayTriangleHitsBothSides() {
        double t = ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 0.5, 0.5, 3, 0, 0, -1);
        near(t, 3.0D, 1.0E-12D, "front hit distance");
        t = ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 0.5, 0.5, -2, 0, 0, 1);
        near(t, 2.0D, 1.0E-12D, "back hit distance (two-sided)");
        double s = 1.0D / Math.sqrt(3.0D);
        t = ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 0.2 - s, 0.3 - s, -s, s, s, s);
        near(t, 1.0D, 1.0E-12D, "oblique hit distance");
    }

    private static void rayTriangleMissesAndParallel() {
        check(Double.isNaN(ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 1.5, 1.5, 3, 0, 0, -1)), "outside hypotenuse");
        check(Double.isNaN(ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, -0.1, 0.5, 3, 0, 0, -1)), "outside leg");
        check(Double.isNaN(ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 0.5, 0.5, 1, 1, 0, 0)), "parallel");
        double behind = ArmorMeshMath.rayTriangle(TRI, 0, 0, 0, 0, 0, 0.5, 0.5, 3, 0, 0, 1);
        check(behind < 0.0D, "triangle behind the start gives a negative parameter");
    }

    private static void rayTriangleSharedEdgeIsWatertight() {
        // Two triangles of a unit quad; rays exactly through the diagonal must hit at least one.
        double[] quad = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0};
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            double a = random.nextDouble();
            double t1 = ArmorMeshMath.rayTriangle(quad, 0, 0, 0, 0, 0, a, a, 1, 0, 0, -1);
            double t2 = ArmorMeshMath.rayTriangle(quad, 9, 0, 0, 0, 0, a, a, 1, 0, 0, -1);
            check(t1 == t1 || t2 == t2, "diagonal ray lost between triangles at " + a);
        }
    }

    private static void offsetTriangleShiftsHit() {
        double t = ArmorMeshMath.rayTriangle(TRI, 0, 0.25, 0, 0, 1, 0.5, 0.5, 3, 0, 0, -1);
        near(t, 2.75D, 1.0E-12D, "cap offset along the normal");
    }

    private static void capsuleAndSphere() {
        near(ArmorMeshMath.raySphere(-5, 0, 0, 1, 0, 0, 0, 0, 0, 1), 4.0D, 1.0E-12D, "sphere entry");
        check(Double.isNaN(ArmorMeshMath.raySphere(0, 0, 0, 1, 0, 0, 0, 0, 0, 1)), "inside start is no entry");
        check(Double.isNaN(ArmorMeshMath.raySphere(-5, 2, 0, 1, 0, 0, 0, 0, 0, 1)), "sphere miss");
        // Capsule along x from 0 to 4, radius 0.5; a vertical ray through x = 2 enters the body.
        near(ArmorMeshMath.rayCapsule(2, 3, 0, 0, -1, 0, 0, 0, 0, 4, 0, 0, 0.5), 2.5D, 1.0E-12D, "capsule body");
        // Beyond the end cap: enters the end sphere.
        near(ArmorMeshMath.rayCapsule(4.3, 3, 0, 0, -1, 0, 0, 0, 0, 4, 0, 0, 0.5),
                3.0D - Math.sqrt(0.25D - 0.09D), 1.0E-12D, "capsule end cap");
        check(Double.isNaN(ArmorMeshMath.rayCapsule(4.6, 3, 0, 0, -1, 0, 0, 0, 0, 4, 0, 0, 0.5)), "capsule miss");
        // Ray along the axis enters the first sphere.
        near(ArmorMeshMath.rayCapsule(-3, 0, 0, 1, 0, 0, 0, 0, 0, 4, 0, 0, 0.5), 2.5D, 1.0E-12D, "axial entry");
    }

    private static void closestPointMatchesSampling() {
        Random random = new Random(11);
        double[] out = new double[3];
        for (int i = 0; i < 300; i++) {
            double[] tri = randomTriangle(random);
            double px = random.nextGaussian() * 2, py = random.nextGaussian() * 2, pz = random.nextGaussian() * 2;
            double exact = ArmorMeshMath.closestPointOnTriangle(tri, 0, px, py, pz, out);
            double sampled = Double.POSITIVE_INFINITY;
            for (int a = 0; a <= 60; a++) {
                for (int b = 0; a + b <= 60; b++) {
                    double u = a / 60.0D, w = b / 60.0D;
                    double x = tri[0] + u * (tri[3] - tri[0]) + w * (tri[6] - tri[0]);
                    double y = tri[1] + u * (tri[4] - tri[1]) + w * (tri[7] - tri[1]);
                    double z = tri[2] + u * (tri[5] - tri[2]) + w * (tri[8] - tri[2]);
                    sampled = Math.min(sampled, (x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
                }
            }
            check(exact <= sampled + 1.0E-12D, "closest point is no worse than sampling");
            check(Math.sqrt(sampled) - Math.sqrt(exact) < 0.2D, "sampling converges to the closest point");
        }
    }

    private static void segmentTriangleMatchesSampling() {
        Random random = new Random(13);
        double[] out = new double[4];
        double[] scratch = new double[4];
        double[] point = new double[3];
        for (int i = 0; i < 300; i++) {
            double[] tri = randomTriangle(random);
            double sx = random.nextGaussian() * 3, sy = random.nextGaussian() * 3, sz = random.nextGaussian() * 3;
            double dx = random.nextGaussian(), dy = random.nextGaussian(), dz = random.nextGaussian();
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            double segment = 1.0D + random.nextDouble() * 5.0D;
            double exact = ArmorMeshMath.segmentTriangle(tri, 0, sx, sy, sz, dx, dy, dz, segment, out, scratch);
            double sampled = Double.POSITIVE_INFINITY;
            for (int k = 0; k <= 400; k++) {
                double t = segment * k / 400.0D;
                sampled = Math.min(sampled, ArmorMeshMath.closestPointOnTriangle(tri, 0,
                        sx + dx * t, sy + dy * t, sz + dz * t, point));
            }
            check(exact <= sampled + 1.0E-10D, "segment distance is no worse than sampling");
            check(Math.sqrt(sampled) - Math.sqrt(exact) < 0.05D, "sampling converges to the segment distance");
            // The reported parameter and surface point realise the reported distance.
            double t = out[0];
            double ex = sx + dx * t - out[1], ey = sy + dy * t - out[2], ez = sz + dz * t - out[3];
            near(ex * ex + ey * ey + ez * ez, exact, 1.0E-9D, "reported points realise the distance");
            check(t >= -1.0E-12D && t <= segment + 1.0E-12D, "parameter within the segment");
        }
    }

    private static void solidAngleOfClosedBox() {
        double[] soup = ArmorMeshTestSupport.boxSoup(-1, -1, -1, 1, 1, 1, false);
        double inside = 0.0D, outside = 0.0D;
        for (int t = 0; t < 12; t++) {
            inside += ArmorMeshMath.solidAngle(soup, t * 9, 0.3, -0.2, 0.1);
            outside += ArmorMeshMath.solidAngle(soup, t * 9, 3.0, 0.2, 0.1);
        }
        near(Math.abs(inside), 4.0D * Math.PI, 1.0E-9D, "winding number 1 inside");
        near(outside, 0.0D, 1.0E-9D, "winding number 0 outside");
    }

    private static void rayAabbSlab() {
        double[] box = {-1, -1, -1, 1, 1, 1};
        near(ArmorMeshMath.rayAabb(box, 0, 0, -5, 0, 0, 1, 0, 0, 10), 4.0D, 1.0E-12D, "slab entry");
        near(ArmorMeshMath.rayAabb(box, 0, 0.5, -5, 0, 0, 1, 0, 0, 10), 3.5D, 1.0E-12D, "padded entry");
        check(Double.isNaN(ArmorMeshMath.rayAabb(box, 0, 0, -5, 2, 0, 1, 0, 0, 10)), "slab miss");
        check(Double.isNaN(ArmorMeshMath.rayAabb(box, 0, 0, -5, 0, 0, 1, 0, 0, 3)), "beyond max distance");
        near(ArmorMeshMath.rayAabb(box, 0, 0, 0, 0, 0, 1, 0, 0, 10), 0.0D, 0.0D, "inside start");
        near(ArmorMeshMath.pointAabbDistanceSquared(box, 0, 3, 0, 0), 4.0D, 1.0E-12D, "point to box");
    }

    private static double[] randomTriangle(Random random) {
        double[] tri = new double[9];
        for (int i = 0; i < 9; i++) tri[i] = random.nextGaussian() * 1.5D;
        return tri;
    }
}
