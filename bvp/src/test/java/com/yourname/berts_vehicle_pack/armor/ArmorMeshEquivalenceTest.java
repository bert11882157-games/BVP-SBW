package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.BoxQuery;
import com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.FakeTarget;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.yourname.berts_vehicle_pack.armor.ArmorMeshTestSupport.v;

/**
 * Proves the mesh path before any hand-authored mesh exists: every shipped box profile, exported as
 * a mesh template, must resolve exactly like its boxes. For each profile, 2400 rays (hull, turret at
 * four yaws, barrel at four elevations) go through the shared resolver for every volume list, and
 * 2000 points go through the inside/distance queries. Checked against both the in-memory export and
 * the checked-in templates of tools/armor_mesh/templates (when the harness runs from the repository).
 */
public final class ArmorMeshEquivalenceTest {
    private static final int EXPECTED_PROFILES = 61;
    private static final int RAYS_PER_CONFIG = 600;
    private static final int POINTS = 2000;
    private static final double[][] CONFIGS = {{0.0D, 0.0D}, {37.5D, 4.0D}, {-121.0D, -6.0D}, {180.0D, 12.0D}};
    private static final double DISTANCE_TOLERANCE = 1.0E-6D;
    private static final double NORMAL_TOLERANCE = 1.0E-6D;
    private static final double TIE = 1.0E-9D;

    private final List<String> failures = new ArrayList<>();
    private long rays;
    private long rayHits;
    private long ties;
    private long points;
    private long legacyNormals;

    public static void main(String[] args) {
        new ArmorMeshEquivalenceTest().run();
    }

    private void run() {
        Path templates = ArmorMeshTestSupport.templateDirectory();
        int profiles = 0;
        int templateProfiles = 0;
        for (String id : ArmorMeshTestSupport.profileIds()) {
            ArmorProfile boxes = ArmorMeshTestSupport.boxProfile(id);
            if (!boxes.hasDebugVolumes()) continue;
            profiles++;
            ArmorProfile memory = ArmorProfiles.withMesh(boxes, ArmorMeshTestSupport.exportTemplate(boxes),
                    "in-memory template");
            compare(boxes, memory, id + " (in-memory export)");
            if (templates != null) {
                Path file = templates.resolve(id + ".armor.geo.json");
                if (!Files.isRegularFile(file)) {
                    fail(id + ": missing template " + file);
                } else {
                    JsonObject geo = ArmorMeshTestSupport.readJsonFile(file);
                    compare(boxes, ArmorProfiles.withMesh(boxes, geo, file.getFileName().toString()),
                            id + " (" + file.getFileName() + ")");
                    templateProfiles++;
                }
            }
            if (failures.size() > 40) break;
        }
        if (profiles != EXPECTED_PROFILES) {
            fail("expected " + EXPECTED_PROFILES + " profiles with volumes, found " + profiles);
        }
        System.out.printf("Mesh/box equivalence: %d profiles, %d checked-in templates%s; %d rays (%d hits, %d exact"
                        + " ties), %d legacy-normal checks, %d points%n", profiles, templateProfiles,
                templates == null ? " (templates directory not found: in-memory export only)" : " at " + templates,
                rays, rayHits, ties, legacyNormals, points);
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(System.out::println);
            throw new AssertionError(failures.size() + " mesh/box mismatches");
        }
        System.out.println("PASS mesh templates resolve exactly like the box profiles (first volume, distance,"
                + " entered-face normal, inside, distance outside) for every profile");
    }

    private void compare(ArmorProfile boxes, ArmorProfile mesh, String label) {
        List<List<ArmorBox>> boxLists = lists(boxes);
        List<List<ArmorBox>> meshLists = lists(mesh);
        if (!mesh.usesArmorMesh()) {
            fail(label + ": mesh profile did not load");
            return;
        }
        if (!mesh.meshWarnings.isEmpty()) {
            fail(label + ": warnings " + mesh.meshWarnings.subList(0, Math.min(3, mesh.meshWarnings.size())));
        }
        for (int list = 0; list < boxLists.size(); list++) {
            compareMetadata(boxLists.get(list), meshLists.get(list), label + " list " + list);
        }
        Random random = new Random(label.hashCode() * 31L + boxes.id.hashCode());
        List<ArmorBox> all = new ArrayList<>();
        boxLists.forEach(all::addAll);
        double inflation = Math.min(0.03D, Math.max(0.005D, boxes.impactTolerance * 0.1D));
        for (double[] config : CONFIGS) {
            FakeTarget target = FakeTarget.of(boxes.id, config[0], config[1]);
            for (int i = 0; i < RAYS_PER_CONFIG; i++) {
                Vec direction = randomUnit(random);
                Vec start;
                ArmorBox aimed = null;
                Vec aimFrame = null;
                if (random.nextInt(10) < 7 && !all.isEmpty()) {
                    aimed = all.get(random.nextInt(all.size()));
                    aimFrame = pointNear(aimed, random, 1.2D);
                    Vec aimHull = ArmorHitResolver.pointToHullFrame(target, aimed, aimFrame);
                    start = aimHull.subtract(direction.scale(2.0D + random.nextDouble() * 6.0D));
                } else {
                    start = v(random.nextDouble() * 8 - 4, random.nextDouble() * 6 - 1, random.nextDouble() * 14 - 7);
                }
                rays++;
                for (int list = 0; list < boxLists.size(); list++) {
                    compareRay(target, boxLists.get(list), meshLists.get(list), start, direction,
                            boxes.impactTolerance, inflation, label + " cfg " + config[0] + " ray " + i);
                }
                if (aimed != null) {
                    compareLegacyNormal(target, aimed, find(meshLists, aimed.name), start, direction,
                            label + " ray " + i);
                }
                ArmorBox nearBox = all.get(random.nextInt(all.size()));
                Vec impact = ArmorHitResolver.pointToHullFrame(target, nearBox, pointNear(nearBox, random, 1.5D));
                compareNearest(target, boxes.eraBoxes, mesh.eraBoxes, impact,
                        Math.max(boxes.impactTolerance, 0.45D), label + " era near " + i);
            }
        }
        FakeTarget rest = FakeTarget.of(boxes.id, 0.0D, 0.0D);
        for (int i = 0; i < POINTS; i++) {
            ArmorBox box = all.get(random.nextInt(all.size()));
            ArmorBox meshBox = find(meshLists, box.name);
            Vec point = pointNear(box, random, 1.25D);
            points++;
            boolean inside = box.distanceOutside(point) == 0.0D;
            if (inside != meshBox.volume.contains(point)) {
                fail(label + " point " + i + " " + box.name + ": inside " + inside + " vs mesh "
                        + meshBox.volume.contains(point));
            }
            double expected = box.distanceOutside(point);
            double actual = meshBox.distanceOutside(point);
            if (!(Math.abs(expected - actual) <= DISTANCE_TOLERANCE)) {
                fail(label + " point " + i + " " + box.name + ": distance outside " + expected + " vs " + actual);
            }
            Vec hullPoint = ArmorHitResolver.pointToHullFrame(rest, box, point);
            for (int list = 0; list < boxLists.size(); list++) {
                compareNearest(rest, boxLists.get(list), meshLists.get(list), hullPoint, boxes.impactTolerance,
                        label + " point " + i + " list " + list);
            }
        }
    }

    private void compareRay(FakeTarget target, List<ArmorBox> boxList, List<ArmorBox> meshList, Vec start,
                            Vec direction, double tolerance, double inflation, String label) {
        ArmorHit boxHit = ArmorHitResolver.findFirstBoxOnRay(target, boxList, start, direction, 12.0D, tolerance);
        ArmorHit meshHit = ArmorHitResolver.findFirstBoxOnRay(target, meshList, start, direction, 12.0D, tolerance);
        if (boxHit == null || meshHit == null) {
            if (boxHit != meshHit) {
                fail(label + ": box hit " + name(boxHit) + " vs mesh hit " + name(meshHit));
            }
            return;
        }
        rayHits++;
        if (!(Math.abs(boxHit.distance - meshHit.distance) <= DISTANCE_TOLERANCE)) {
            fail(label + ": distance " + boxHit.distance + " (" + boxHit.plate.name + ") vs "
                    + meshHit.distance + " (" + meshHit.plate.name + ")");
            return;
        }
        if (!boxHit.plate.name.equals(meshHit.plate.name)) {
            // Coincident entries (shared faces): the list order decides, and both paths must agree
            // that the other winner enters at the same distance.
            double boxOther = frameRay(target, find(boxList, meshHit.plate.name), start, direction, inflation);
            if (Math.abs(boxOther - boxHit.distance) <= TIE + DISTANCE_TOLERANCE) {
                ties++;
                return;
            }
            fail(label + ": first volume " + boxHit.plate.name + " vs " + meshHit.plate.name);
            return;
        }
        if (!near(boxHit.hullImpact, meshHit.hullImpact, DISTANCE_TOLERANCE)) {
            fail(label + ": impact point differs for " + boxHit.plate.name);
        }
        if (meshHit.normal == null) {
            fail(label + ": mesh hit carries no normal");
            return;
        }
        Vec entered = trueBoxEntryNormal(target, boxHit.plate, start, direction, inflation);
        if (!near(entered, meshHit.normal, NORMAL_TOLERANCE)) {
            fail(label + ": entered-face normal " + text(entered) + " vs mesh " + text(meshHit.normal) + " on "
                    + boxHit.plate.name + " at " + boxHit.distance);
        }
    }

    /** At zero skin the legacy box normal at the hit point is the entered face, so all three agree. */
    private void compareLegacyNormal(FakeTarget target, ArmorBox box, ArmorBox mesh, Vec start, Vec direction,
                                     String label) {
        Vec frameStart = ArmorHitResolver.pointToBoxFrame(target, box, start);
        Vec frameDirection = ArmorHitResolver.directionToBoxFrame(target, box, direction.normalize()).normalize();
        double boxDistance = box.rayHitDistance(frameStart, frameDirection, 12.0D, 0.0D);
        double meshDistance = mesh.rayHitDistance(frameStart, frameDirection, 12.0D, 0.0D);
        if (Double.isNaN(boxDistance) != Double.isNaN(meshDistance)) {
            fail(label + ": zero-skin hit " + boxDistance + " vs " + meshDistance + " on " + box.name);
            return;
        }
        if (Double.isNaN(boxDistance) || boxDistance == 0.0D) return;
        if (!(Math.abs(boxDistance - meshDistance) <= DISTANCE_TOLERANCE)) {
            fail(label + ": zero-skin distance " + boxDistance + " vs " + meshDistance + " on " + box.name);
            return;
        }
        legacyNormals++;
        Vec legacy = box.normalAt(frameStart.add(frameDirection.scale(boxDistance)));
        Vec meshNormal = mesh.volume.rayEntryNormal(frameStart, frameDirection, 12.0D, 0.0D);
        if (!near(legacy, meshNormal, NORMAL_TOLERANCE)) {
            fail(label + ": legacy normal " + text(legacy) + " vs mesh " + text(meshNormal) + " on " + box.name);
        }
    }

    private void compareNearest(FakeTarget target, List<ArmorBox> boxList, List<ArmorBox> meshList, Vec hullPoint,
                                double tolerance, String label) {
        if (boxList.isEmpty()) return;
        BoxQuery boxQuery = ArmorHitResolver.findNearestBoxAtImpact(target, boxList, hullPoint, tolerance);
        BoxQuery meshQuery = ArmorHitResolver.findNearestBoxAtImpact(target, meshList, hullPoint, tolerance);
        if ((boxQuery.hit() == null) != (meshQuery.hit() == null)) {
            fail(label + ": proximity hit " + name(boxQuery.hit()) + " vs " + name(meshQuery.hit()));
            return;
        }
        if (!(Math.abs(boxQuery.nearest().distance() - meshQuery.nearest().distance()) <= DISTANCE_TOLERANCE)) {
            fail(label + ": nearest distance " + boxQuery.nearest().distance() + " vs "
                    + meshQuery.nearest().distance());
            return;
        }
        if (!boxQuery.nearest().box().name.equals(meshQuery.nearest().box().name)) {
            ArmorBox other = find(boxList, meshQuery.nearest().box().name);
            Vec frame = ArmorHitResolver.pointToBoxFrame(target, other, hullPoint);
            if (Math.abs(other.distanceOutside(frame) - boxQuery.nearest().distance()) <= TIE + DISTANCE_TOLERANCE) {
                ties++;
                return;
            }
            fail(label + ": nearest volume " + boxQuery.nearest().box().name + " vs "
                    + meshQuery.nearest().box().name);
        }
    }

    private void compareMetadata(List<ArmorBox> boxes, List<ArmorBox> meshes, String label) {
        if (boxes.size() != meshes.size()) {
            fail(label + ": " + boxes.size() + " boxes vs " + meshes.size() + " meshes");
            return;
        }
        for (int i = 0; i < boxes.size(); i++) {
            ArmorBox box = boxes.get(i);
            ArmorBox mesh = meshes.get(i);
            String where = label + " " + box.name;
            if (!box.name.equals(mesh.name) || box.armorMm != mesh.armorMm || !box.frame.equals(mesh.frame)
                    || !box.module.equals(mesh.module) || box.unified != mesh.unified
                    || !box.eraType.equals(mesh.eraType) || box.kineticProtectionMm != mesh.kineticProtectionMm
                    || box.chemicalProtectionMm != mesh.chemicalProtectionMm) {
                fail(where + ": metadata differs from " + mesh.name + " " + mesh.armorMm + " " + mesh.frame);
                continue;
            }
            if (!mesh.isMesh() || !((ArmorMeshVolume) mesh.volume).isClosed()
                    || ((ArmorMeshVolume) mesh.volume).partCount() != 1 || !((ArmorMeshVolume) mesh.volume).isConvexPart(0)) {
                fail(where + ": template volume is not one closed convex part");
            }
            if (!near(box.centroid(), mesh.centroid(), 1.0E-9D)
                    || Math.abs(box.volume.volume() - mesh.volume.volume()) > 1.0E-9D
                    || Math.abs(box.minFrameY() - mesh.minFrameY()) > 1.0E-9D) {
                fail(where + ": centroid/volume/minY differ");
            }
            double[] a = box.volume.bounds();
            double[] b = mesh.volume.bounds();
            for (int k = 0; k < 6; k++) {
                if (Math.abs(a[k] - b[k]) > 1.0E-9D) {
                    fail(where + ": bounds differ");
                    break;
                }
            }
        }
    }

    private static double frameRay(FakeTarget target, ArmorBox box, Vec start, Vec direction, double inflation) {
        if (box == null) return Double.NaN;
        Vec frameStart = ArmorHitResolver.pointToBoxFrame(target, box, start);
        Vec frameDirection = ArmorHitResolver.directionToBoxFrame(target, box, direction.normalize()).normalize();
        return box.rayHitDistance(frameStart, frameDirection, 12.0D, inflation);
    }

    private static Vec trueBoxEntryNormal(FakeTarget target, ArmorBox box, Vec start, Vec direction,
                                          double inflation) {
        Vec frameStart = ArmorHitResolver.pointToBoxFrame(target, box, start);
        Vec frameDirection = ArmorHitResolver.directionToBoxFrame(target, box, direction.normalize()).normalize();
        return box.volume.rayEntryNormal(frameStart, frameDirection, 12.0D, inflation);
    }

    private static Vec pointNear(ArmorBox box, Random random, double spread) {
        double[] b = box.volume.bounds();
        double cx = (b[0] + b[3]) * 0.5D, cy = (b[1] + b[4]) * 0.5D, cz = (b[2] + b[5]) * 0.5D;
        return v(cx + (random.nextDouble() - 0.5D) * (b[3] - b[0] + 0.1D) * spread,
                cy + (random.nextDouble() - 0.5D) * (b[4] - b[1] + 0.1D) * spread,
                cz + (random.nextDouble() - 0.5D) * (b[5] - b[2] + 0.1D) * spread);
    }

    private static Vec randomUnit(Random random) {
        while (true) {
            Vec value = v(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            if (value.length() > 1.0E-3D) return value.normalize();
        }
    }

    private static List<List<ArmorBox>> lists(ArmorProfile profile) {
        return List.of(profile.plates, profile.eraBoxes, profile.engineBoxes, profile.ammoRacks,
                profile.moduleBoxes, profile.trackBoxes, profile.sensitiveInternals);
    }

    private static ArmorBox find(List<List<ArmorBox>> lists, String name) {
        for (List<ArmorBox> list : lists) {
            ArmorBox box = find(list, name);
            if (box != null) return box;
        }
        throw new AssertionError("no volume " + name);
    }

    private static ArmorBox find(List<ArmorBox> list, String name) {
        for (ArmorBox box : list) if (box.name.equals(name)) return box;
        return null;
    }

    private static boolean near(Vec a, Vec b, double tolerance) {
        return a != null && b != null && Math.abs(a.x - b.x) <= tolerance && Math.abs(a.y - b.y) <= tolerance
                && Math.abs(a.z - b.z) <= tolerance;
    }

    private static String name(ArmorHit hit) {
        return hit == null ? "none" : hit.plate.name + "@" + hit.distance;
    }

    private static String text(Vec value) {
        return value == null ? "null" : String.format("(%.9f, %.9f, %.9f)", value.x, value.y, value.z);
    }

    private void fail(String message) {
        failures.add(message);
    }
}
