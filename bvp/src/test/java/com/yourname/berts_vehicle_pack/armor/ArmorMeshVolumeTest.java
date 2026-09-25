package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.SegmentApproach;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.boxSoup;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.check;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.concat;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.mesh;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.near;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.nearVec;
import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.v;

/** Headless checks for mesh volumes: winding repair, convex and concave parts, BVH, skins, reports. */
public final class ArmorMeshVolumeTest {
    public static void main(String[] args) {
        boxMeshMatchesBoxVolume();
        invertedBoxIsTurnedOutward();
        concaveLShapeMatchesAnalyticUnion();
        concaveSkinIsDistanceToSurface();
        bvhMatchesBruteForce();
        openSurfaceIsTwoSided();
        sharpWedgeSkinIsNotCulled();
        slopedPlateNormalIsTriangleNormal();
        nonManifoldAndDegenerateAreReported();
        closestApproachReportsGap();
        multiPartVolumeIsUnion();
        System.out.println("PASS mesh volumes: box parity, winding repair, concave union, BVH, open surfaces, skins,"
                + " reports");
    }

    private static void boxMeshMatchesBoxVolume() {
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(boxSoup(-0.5, -0.2, -1.0, 0.5, 0.2, 1.0, true), warnings);
        check(warnings.isEmpty(), "clean box has no warnings: " + warnings);
        check(mesh.report().flippedTriangles > 0, "scrambled winding was repaired");
        check(mesh.isClosed() && mesh.isConvexPart(0), "box mesh is a closed convex part");
        ArmorBoxVolume box = new ArmorBoxVolume(v(0, 0, 0), v(0.5, 0.2, 1.0), v(0, 0, 0));
        near(mesh.volume(), box.volume(), 1.0E-12D, "volume");
        nearVec(mesh.centroid(), v(0, 0, 0), 1.0E-12D, "centroid");
        near(mesh.minY(), -0.2D, 0.0D, "min y");
        Random random = new Random(3);
        int hits = 0;
        for (int i = 0; i < 4000; i++) {
            Vec start = v(random.nextGaussian() * 2, random.nextGaussian() * 2, random.nextGaussian() * 2);
            Vec target = v(random.nextDouble() - 0.5, (random.nextDouble() - 0.5) * 0.4, random.nextDouble() * 2 - 1);
            Vec direction = target.subtract(start).normalize();
            for (double inflation : new double[] {0.0D, 0.025D}) {
                double expected = box.rayHitDistance(start, direction, 12.0D, inflation);
                double actual = mesh.rayHitDistance(start, direction, 12.0D, inflation);
                check(Double.isNaN(expected) == Double.isNaN(actual), "hit agreement " + i);
                if (Double.isNaN(expected)) continue;
                hits++;
                near(actual, expected, 1.0E-9D, "entry distance " + i);
                nearVec(mesh.rayEntryNormal(start, direction, 12.0D, inflation),
                        box.rayEntryNormal(start, direction, 12.0D, inflation), 1.0E-9D, "entered face " + i);
            }
            Vec point = v(random.nextGaussian(), random.nextGaussian() * 0.4, random.nextGaussian() * 1.5);
            check(mesh.contains(point) == box.contains(point), "inside agreement " + i);
            near(mesh.distanceOutside(point), box.distanceOutside(point), 1.0E-9D, "distance outside " + i);
        }
        check(hits > 3000, "enough hits: " + hits);
    }

    private static void invertedBoxIsTurnedOutward() {
        double[] soup = boxSoup(0, 0, 0, 1, 1, 1, false);
        for (int t = 0; t < 12; t++) { // reverse every triangle
            for (int k = 0; k < 3; k++) {
                double swap = soup[t * 9 + 3 + k];
                soup[t * 9 + 3 + k] = soup[t * 9 + 6 + k];
                soup[t * 9 + 6 + k] = swap;
            }
        }
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(soup, warnings);
        check(mesh.report().invertedParts == 1, "inside-out part detected");
        nearVec(mesh.rayEntryNormal(v(0.5, 0.5, -3), v(0, 0, 1), 12, 0), v(0, 0, -1), 1.0E-12D, "outward normal");
        nearVec(mesh.normalAt(v(0.5, 2, 0.5)), v(0, 1, 0), 1.0E-12D, "outward nearest normal");
    }

    /** L-shaped prism: the union of [0,2]x[0,1]x[0,1] and [0,1]x[1,2]x[0,1], as one closed concave mesh. */
    private static double[] lPrism() {
        double[][] polygon = {{0, 0}, {2, 0}, {2, 1}, {1, 1}, {1, 2}, {0, 2}};
        List<double[]> tris = new ArrayList<>();
        for (int i = 1; i + 1 < polygon.length; i++) { // fan from (0,0): the L is star-shaped about it
            tris.add(new double[] {0, 0, 0, polygon[i + 1][0], polygon[i + 1][1], 0, polygon[i][0], polygon[i][1], 0});
            tris.add(new double[] {0, 0, 1, polygon[i][0], polygon[i][1], 1, polygon[i + 1][0], polygon[i + 1][1], 1});
        }
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            tris.add(new double[] {a[0], a[1], 0, b[0], b[1], 0, b[0], b[1], 1});
            tris.add(new double[] {a[0], a[1], 0, b[0], b[1], 1, a[0], a[1], 1});
        }
        return concat(tris.toArray(new double[0][]));
    }

    private static final ArmorBoxVolume L_A = new ArmorBoxVolume(v(1, 0.5, 0.5), v(1, 0.5, 0.5), v(0, 0, 0));
    private static final ArmorBoxVolume L_B = new ArmorBoxVolume(v(0.5, 1.5, 0.5), v(0.5, 0.5, 0.5), v(0, 0, 0));

    private static void concaveLShapeMatchesAnalyticUnion() {
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(lPrism(), warnings);
        check(warnings.isEmpty(), "L prism is clean: " + warnings);
        check(mesh.isClosed() && !mesh.isConvexPart(0), "L prism is closed and concave");
        near(mesh.volume(), 3.0D, 1.0E-12D, "L volume");
        Random random = new Random(5);
        for (int i = 0; i < 3000; i++) {
            Vec point = v(random.nextDouble() * 3 - 0.5, random.nextDouble() * 3 - 0.5, random.nextDouble() * 2 - 0.5);
            boolean inside = L_A.contains(point) || L_B.contains(point);
            check(mesh.contains(point) == inside, "L inside at " + i);
            double expected = inside ? 0.0D : Math.min(L_A.distanceOutside(point), L_B.distanceOutside(point));
            near(mesh.distanceOutside(point), expected, 1.0E-9D, "L distance " + i);
            Vec start = v(random.nextGaussian() * 3 + 1, random.nextGaussian() * 3 + 1, random.nextGaussian() * 3);
            Vec direction = v(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            double a = L_A.rayHitDistance(start, direction, 12, 0);
            double b = L_B.rayHitDistance(start, direction, 12, 0);
            double analytic = Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
            double actual = mesh.rayHitDistance(start, direction, 12, 0);
            check(Double.isNaN(analytic) == Double.isNaN(actual), "L ray agreement " + i);
            if (!Double.isNaN(analytic)) near(actual, analytic, 1.0E-9D, "L ray distance " + i);
        }
        // A ray into the inner corner region enters the +x face of the upper arm, not the outer box.
        near(mesh.rayHitDistance(v(3, 1.5, 0.5), v(-1, 0, 0), 12, 0), 2.0D, 1.0E-12D, "inner notch entry");
        nearVec(mesh.rayEntryNormal(v(3, 1.5, 0.5), v(-1, 0, 0), 12, 0), v(1, 0, 0), 1.0E-12D, "inner notch face");
        near(mesh.rayHitDistance(v(0.5, 0.5, 0.5), v(1, 0, 0), 12, 0), 0.0D, 0.0D, "inside start");
    }

    private static void concaveSkinIsDistanceToSurface() {
        ArmorMeshVolume mesh = mesh(lPrism(), new ArrayList<>());
        double skin = 0.05D;
        Random random = new Random(17);
        int grazes = 0;
        for (int i = 0; i < 3000; i++) {
            Vec start = v(random.nextGaussian() * 3 + 1, random.nextGaussian() * 3 + 1, random.nextGaussian() * 3);
            Vec direction = v(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            if (mesh.distanceOutside(start) <= skin) continue;
            double exact = mesh.rayHitDistance(start, direction, 12, 0);
            double grown = mesh.rayHitDistance(start, direction, 12, skin);
            SegmentApproach approach = mesh.closestApproach(start, direction, 12);
            if (!Double.isNaN(exact)) {
                check(!Double.isNaN(grown) && grown <= exact + 1.0E-12D, "skin never delays an entry " + i);
            }
            if (approach.gap() < skin - 1.0E-9D) {
                check(!Double.isNaN(grown), "passing within the skin is a hit " + i);
            }
            if (approach.gap() > skin + 1.0E-9D) {
                check(Double.isNaN(grown), "passing outside the skin is a miss " + i);
            }
            if (Double.isNaN(exact) && !Double.isNaN(grown)) {
                grazes++;
                Vec entry = start.add(direction.scale(grown));
                near(mesh.distanceOutside(entry), skin, 1.0E-7D, "graze entry lies on the skin " + i);
            }
        }
        check(grazes > 5, "some rays only graze the skin: " + grazes);
    }

    private static void bvhMatchesBruteForce() {
        // A UV sphere of radius 1 (many triangles, closed, convex) plus a concave dent-free torus-like ring.
        List<double[]> tris = new ArrayList<>();
        int slices = 36, stacks = 18;
        for (int i = 0; i < stacks; i++) {
            double t0 = Math.PI * i / stacks, t1 = Math.PI * (i + 1) / stacks;
            for (int j = 0; j < slices; j++) {
                double p0 = 2 * Math.PI * j / slices, p1 = 2 * Math.PI * (j + 1) / slices;
                double[] a = sphere(t0, p0), b = sphere(t1, p0), c = sphere(t1, p1), d = sphere(t0, p1);
                if (i > 0) tris.add(concat(a, b, d));
                if (i < stacks - 1) tris.add(concat(b, c, d));
            }
        }
        double[] soup = concat(tris.toArray(new double[0][]));
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(soup, warnings);
        check(warnings.isEmpty(), "sphere is clean: " + warnings);
        check(mesh.isClosed() && !mesh.isConvexPart(0), "lumpy sphere is closed and concave");
        check(mesh.triangleCount() > 1000, "many triangles");
        Random random = new Random(23);
        for (int i = 0; i < 2000; i++) {
            double sx = random.nextGaussian() * 3, sy = random.nextGaussian() * 3, sz = random.nextGaussian() * 3;
            Vec start = v(sx, sy, sz);
            Vec direction = v(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            if (mesh.contains(start)) continue;
            double brute = Double.NaN;
            for (int t = 0; t < soup.length / 9; t++) {
                double hit = ArmorMeshMath.rayTriangle(soup, t * 9, 0, 0, 0, 0, sx, sy, sz,
                        direction.x, direction.y, direction.z);
                if (hit >= 0 && hit <= 12 && !(brute <= hit)) brute = hit;
            }
            double actual = mesh.rayHitDistance(start, direction, 12, 0);
            check(Double.isNaN(brute) == Double.isNaN(actual), "BVH ray agreement " + i);
            if (!Double.isNaN(brute)) near(actual, brute, 1.0E-12D, "BVH ray distance " + i);
            double nearest = Double.POSITIVE_INFINITY;
            double[] out = new double[3];
            for (int t = 0; t < soup.length / 9; t++) {
                nearest = Math.min(nearest, ArmorMeshMath.closestPointOnTriangle(soup, t * 9, sx, sy, sz, out));
            }
            near(mesh.distanceOutside(start), Math.sqrt(nearest), 1.0E-12D, "BVH nearest " + i);
        }
    }

    /** A lumpy (non-convex) closed sphere, so rays go through the BVH instead of face planes. */
    private static double[] sphere(double theta, double phi) {
        double radius = 1.0D + 0.3D * Math.sin(3.0D * phi) * Math.sin(2.0D * theta);
        return new double[] {radius * Math.sin(theta) * Math.cos(phi), radius * Math.cos(theta),
                radius * Math.sin(theta) * Math.sin(phi)};
    }

    private static void openSurfaceIsTwoSided() {
        double[] quad = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0};
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(quad, warnings);
        check(warnings.size() == 1 && warnings.get(0).contains("not closed"), "open surface warned: " + warnings);
        check(!mesh.isClosed(), "open surface is not closed");
        near(mesh.rayHitDistance(v(0.4, 0.3, 2), v(0, 0, -1), 12, 0), 2.0D, 1.0E-12D, "front side hit");
        near(mesh.rayHitDistance(v(0.4, 0.3, -3), v(0, 0, 1), 12, 0), 3.0D, 1.0E-12D, "back side hit");
        nearVec(mesh.rayEntryNormal(v(0.4, 0.3, 2), v(0, 0, -1), 12, 0), v(0, 0, 1), 1.0E-12D, "normal faces the shot");
        nearVec(mesh.rayEntryNormal(v(0.4, 0.3, -3), v(0, 0, 1), 12, 0), v(0, 0, -1), 1.0E-12D,
                "normal faces the shot from behind");
        check(!mesh.contains(v(0.5, 0.5, 0)), "a surface has no inside");
        near(mesh.distanceOutside(v(0.5, 0.5, -0.25)), 0.25D, 1.0E-12D, "distance to the sheet");
        nearVec(mesh.normalAt(v(0.5, 0.5, -0.25)), v(0, 0, -1), 1.0E-12D, "sheet normal toward the point");
        near(mesh.rayHitDistance(v(0.5, 0.5, 0.03), v(0, 1, 0), 12, 0.05D), 0.0D, 0.0D, "start within the skin");
    }

    private static void sharpWedgeSkinIsNotCulled() {
        // Wedge prism with a 10 degree apex at the origin pointing along -x, extruded along z.
        double half = Math.toRadians(5.0D);
        double length = 2.0D;
        double y = Math.tan(half) * length;
        double[] a = {0, 0, 0}, b = {length, y, 0}, c = {length, -y, 0};
        double[] a2 = {0, 0, 1}, b2 = {length, y, 1}, c2 = {length, -y, 1};
        double[] soup = concat(a, c, b, a2, b2, c2,
                a, b, b2, a, b2, a2,
                a, a2, c2, a, c2, c,
                b, c, c2, b, c2, b2);
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(soup, warnings);
        check(warnings.isEmpty() && mesh.isConvexPart(0), "wedge is a clean convex part: " + warnings);
        double skin = 0.03D;
        double apexReach = skin / Math.sin(half); // plane-offset apex moves this far along -x
        check(mesh.skinPad(skin) >= apexReach, "skin pad covers the displaced apex");
        // A vertical ray just inside the displaced apex hits; just outside it misses.
        double inside = -0.95D * apexReach;
        double outside = -1.05D * apexReach;
        check(!Double.isNaN(mesh.rayHitDistance(v(inside, 3, 0.5), v(0, -1, 0), 12, skin)),
                "ray through the grown apex hits");
        check(Double.isNaN(mesh.rayHitDistance(v(outside, 3, 0.5), v(0, -1, 0), 12, skin)),
                "ray beyond the grown apex misses");
        // The profile-level bounds cull uses the same pad through ArmorBox.
        ArmorProfiles.ArmorBox box = ArmorProfiles.ArmorBox.mesh("wedge", 10, mesh, "hull", "", true, "", 0, 0);
        check(!Double.isNaN(box.boundsEntry(v(inside, 3, 0.5), v(0, -1, 0), 12, skin)), "bounds keep the apex hit");
    }

    private static void slopedPlateNormalIsTriangleNormal() {
        // A glacis slab tilted 30 degrees about X, exported as triangles.
        ArmorBoxVolume box = new ArmorBoxVolume(v(0, 1, -2), v(0.8, 0.05, 0.6), v(-30, 0, 0));
        double[] soup = new double[12 * 9];
        final int[] cursor = {0};
        box.forEachTriangle((ax, ay, az, bx, by, bz, cx, cy, cz) -> {
            double[] t = {ax, ay, az, bx, by, bz, cx, cy, cz};
            System.arraycopy(t, 0, soup, cursor[0] * 9, 9);
            cursor[0]++;
        });
        ArmorMeshVolume mesh = mesh(soup, new ArrayList<>());
        Vec shotStart = v(0.1, 1.2, -6);
        Vec shot = v(0, 1, -2).subtract(shotStart).normalize();
        Vec entered = mesh.rayEntryNormal(shotStart, shot, 12, 0);
        nearVec(entered, box.rayEntryNormal(shotStart, shot, 12, 0), 1.0E-12D, "true face normal");
        check(Math.abs(entered.dot(shot)) < 0.999D, "a sloped face gives a real angle");
        Vec dominant = mesh.dominantFaceNormal(v(0, 0.2, -1));
        nearVec(dominant, box.dominantFaceNormal(v(0, 0.2, -1)), 1.0E-12D, "dominant face");
    }

    private static void nonManifoldAndDegenerateAreReported() {
        double[] fan = {0, 0, 0, 1, 0, 0, 0, 1, 0,
                0, 0, 0, 1, 0, 0, 0, -1, 0,
                0, 0, 0, 1, 0, 0, 0, 0, 1,
                0, 0, 0, 0, 0, 0, 1, 1, 1};
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(fan, warnings);
        check(mesh.report().nonManifoldEdges == 1, "one edge shared by three triangles");
        check(mesh.report().degenerate == 1, "zero-area triangle dropped");
        String all = String.join("\n", warnings);
        check(all.contains("non-manifold") && all.contains("degenerate") && all.contains("not closed"),
                "warnings name every problem: " + all);
        check(mesh.triangleCount() == 3, "three usable triangles");
    }

    private static void closestApproachReportsGap() {
        ArmorMeshVolume mesh = mesh(boxSoup(-1, -1, -1, 1, 1, 1, false), new ArrayList<>());
        SegmentApproach under = mesh.closestApproach(v(-5, -1.5, 0.3), v(1, 0, 0), 12);
        near(under.gap(), 0.5D, 1.0E-12D, "gap under the box");
        near(under.frameEntry().y, -1.0D, 1.0E-12D, "surface point on the bottom face");
        SegmentApproach through = mesh.closestApproach(v(-5, 0.2, 0.1), v(1, 0, 0), 12);
        near(through.gap(), 0.0D, 0.0D, "touching segment");
        near(through.rayDistance(), 4.0D, 1.0E-12D, "entry parameter");
        SegmentApproach edge = mesh.closestApproach(v(2, 2, -5), v(0, 0, 1), 12);
        near(edge.gap(), Math.sqrt(2.0D), 1.0E-12D, "edge pass gap");
    }

    private static void multiPartVolumeIsUnion() {
        double[] soup = concat(boxSoup(0, 0, 0, 1, 1, 1, false), boxSoup(3, 0, 0, 4, 1, 1, true));
        List<String> warnings = new ArrayList<>();
        ArmorMeshVolume mesh = mesh(soup, warnings);
        check(warnings.isEmpty() && mesh.partCount() == 2, "two clean parts");
        near(mesh.rayHitDistance(v(-2, 0.5, 0.5), v(1, 0, 0), 12, 0), 2.0D, 1.0E-12D, "first part first");
        near(mesh.rayHitDistance(v(2, 0.5, 0.5), v(1, 0, 0), 12, 0), 1.0D, 1.0E-12D, "second part");
        near(mesh.rayHitDistance(v(5, 0.5, 0.5), v(-1, 0, 0), 12, 0), 1.0D, 1.0E-12D, "reverse order");
        check(mesh.contains(v(3.5, 0.5, 0.5)) && !mesh.contains(v(2, 0.5, 0.5)), "union inside");
        near(mesh.volume(), 2.0D, 1.0E-12D, "union volume");
        nearVec(mesh.centroid(), v(2, 0.5, 0.5), 1.0E-12D, "union centroid");
    }
}
