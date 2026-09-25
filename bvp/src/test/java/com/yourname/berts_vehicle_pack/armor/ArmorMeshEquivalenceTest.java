package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Proves the mesh path against the box path for every shipped box profile: each box is written as
 * a closed 6-quad Blockbench mesh (the same conversion tools/armor_mesh/export_templates.py uses),
 * loaded through {@link ArmorMeshLoader}, and then both are queried with thousands of random rays
 * and points in every volume's own frame. First volume hit, entry distance, entry normal,
 * containment and distance to the solid must agree.
 */
public final class ArmorMeshEquivalenceTest {
    private static final int RAYS_PER_PROFILE = 2500;
    private static final int POINTS_PER_PROFILE = 1500;
    private static final double EPS = 1.0E-6D;

    public static void main(String[] args) throws Exception {
        List<String> ids = profileIds();
        int profiles = 0;
        long rays = 0;
        long points = 0;
        long edgeTies = 0;
        List<String> failures = new ArrayList<>();
        for (String id : ids) {
            JsonObject root = read("/data/berts_vehicle_pack/armor/" + id + ".json");
            ArmorProfile boxes = ArmorProfiles.parse(id, root);
            List<ArmorBox> all = everyVolume(boxes);
            if (all.isEmpty()) continue;
            ArmorProfile mesh = ArmorProfiles.withMesh(boxes, geoFor(boxes), "equivalence-test");
            List<ArmorBox> meshAll = everyVolume(mesh);
            if (meshAll.size() != all.size()) {
                failures.add(id + ": volume count " + all.size() + " boxes vs " + meshAll.size() + " meshes");
                continue;
            }
            for (ArmorBox m : meshAll) {
                if (!m.isMesh()) failures.add(id + ": volume " + m.name + " was not replaced by a mesh");
            }
            Map<String, ArmorBox> meshByName = new HashMap<>();
            for (ArmorBox m : meshAll) meshByName.put(key(m), m);
            profiles++;
            Random random = new Random(id.hashCode() * 31L + 7L);
            double[] bounds = profileBounds(all);
            // Rays: from outside the profile bounds through a random interior point, per frame group.
            for (int i = 0; i < RAYS_PER_PROFILE; i++) {
                ArmorBox anchor = all.get(random.nextInt(all.size()));
                String frame = anchor.frame;
                Vec target = randomPointNear(anchor, random, 0.6D);
                Vec direction = randomUnit(random);
                Vec start = target.subtract(direction.scale(8.0D));
                double inflation = (i % 3 == 0) ? 0.0D : 0.025D;
                Hit b = first(all, frame, start, direction, 16.0D, inflation);
                Hit m = first(meshAll, frame, start, direction, 16.0D, inflation);
                rays++;
                if (b == null && m == null) continue;
                if ((b == null) != (m == null)) {
                    if (grazing(b, m)) { edgeTies++; continue; }
                    failures.add(id + ": ray " + i + " hit " + describe(b) + " vs mesh " + describe(m));
                    continue;
                }
                if (Math.abs(b.distance - m.distance) > (inflation == 0.0D ? EPS : inflation * 1.5D)) {
                    failures.add(id + ": ray " + i + " distance " + b.distance + " vs " + m.distance
                            + " (" + b.box.name + " / " + m.box.name + ")");
                    continue;
                }
                if (!key(b.box).equals(key(m.box))) {
                    if (Math.abs(b.distance - m.distance) < 1.0E-7D || inflation > 0.0D) { edgeTies++; continue; }
                    failures.add(id + ": ray " + i + " first volume " + b.box.name + " vs " + m.box.name);
                    continue;
                }
                if (inflation == 0.0D && b.distance > 1.0E-9D) {
                    Vec nb = b.box.volume.rayEntryNormal(b.start, b.dir, 16.0D, 0.0D);
                    Vec nm = m.box.volume.rayEntryNormal(m.start, m.dir, 16.0D, 0.0D);
                    if (nb == null || nm == null) {
                        failures.add(id + ": ray " + i + " missing entry normal");
                    } else if (nb.subtract(nm).length() > 1.0E-5D) {
                        Vec p = b.start.add(b.dir.scale(b.distance));
                        if (nearBoxEdge(b.box, p)) { edgeTies++; continue; }
                        failures.add(id + ": ray " + i + " normal " + fmt(nb) + " vs " + fmt(nm) + " on " + b.box.name);
                    }
                }
            }
            // Points: containment and distance to the solid, volume by volume.
            for (int i = 0; i < POINTS_PER_PROFILE; i++) {
                ArmorBox box = all.get(random.nextInt(all.size()));
                ArmorBox m = meshByName.get(key(box));
                Vec p = randomPointNear(box, random, 0.3D);
                points++;
                boolean inB = box.volume.contains(p);
                boolean inM = m.volume.contains(p);
                double dB = box.distanceOutside(p);
                double dM = m.distanceOutside(p);
                if (inB != inM && dB > 1.0E-6D) failures.add(id + ": point " + fmt(p) + " inside " + inB + " vs " + inM + " on " + box.name);
                if (Math.abs(dB - dM) > EPS) failures.add(id + ": point " + fmt(p) + " distance " + dB + " vs " + dM + " on " + box.name);
            }
            if (failures.size() > 60) break;
        }
        System.out.printf(Locale.ROOT, "profiles=%d rays=%d points=%d edge-ties=%d failures=%d%n",
                profiles, rays, points, edgeTies, failures.size());
        for (int i = 0; i < Math.min(40, failures.size()); i++) System.out.println("FAIL " + failures.get(i));
        if (profiles < 50) throw new AssertionError("expected the 61 shipped box profiles, tested " + profiles);
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " box/mesh mismatches");
        if (edgeTies > rays / 200) throw new AssertionError("too many edge ties: " + edgeTies);
        System.out.println("PASS box and mesh armor volumes agree for every shipped profile");
    }

    private record Hit(ArmorBox box, double distance, Vec start, Vec dir) { }

    private static Hit first(List<ArmorBox> volumes, String frame, Vec start, Vec direction, double max, double inflation) {
        Hit best = null;
        for (ArmorBox box : volumes) {
            if (!box.frame.equals(frame)) continue;
            double t = box.rayHitDistance(start, direction, max, inflation);
            if (Double.isFinite(t) && t >= 0.0D && (best == null || t < best.distance)) {
                best = new Hit(box, t, start, direction);
            }
        }
        return best;
    }

    private static boolean grazing(Hit b, Hit m) {
        Hit h = b != null ? b : m;
        // A ray that only touches an edge or corner can be reported by one representation and not the other.
        Vec p = h.start.add(h.dir.scale(h.distance));
        return nearBoxEdge(h.box, p) || h.box.volume.distanceOutside(p.add(h.dir.scale(1.0E-5D))) > 0.0D;
    }

    /** True when the point lies within 1e-5 of two faces of the box (an edge or corner). */
    private static boolean nearBoxEdge(ArmorBox box, Vec p) {
        Vec local = p.subtract(box.center);
        Vec r = box.rotationDeg;
        local = local.rotateZ(-r.z).rotateY(-r.y).rotateX(-r.x);
        int close = 0;
        if (Math.abs(Math.abs(local.x) - box.halfSize.x) < 1.0E-5D) close++;
        if (Math.abs(Math.abs(local.y) - box.halfSize.y) < 1.0E-5D) close++;
        if (Math.abs(Math.abs(local.z) - box.halfSize.z) < 1.0E-5D) close++;
        return close >= 2;
    }

    private static String describe(Hit h) {
        return h == null ? "nothing" : h.box.name + "@" + h.distance;
    }

    private static String key(ArmorBox b) {
        return b.frame + "/" + b.name.toLowerCase(Locale.ROOT);
    }

    private static List<ArmorBox> everyVolume(ArmorProfile p) {
        List<ArmorBox> all = new ArrayList<>();
        all.addAll(p.plates);
        all.addAll(p.eraBoxes);
        all.addAll(p.moduleBoxes);
        all.addAll(p.ammoRacks);
        all.addAll(p.trackBoxes);
        all.addAll(p.engineBoxes);
        all.addAll(p.sensitiveInternals);
        return all;
    }

    private static Vec randomPointNear(ArmorBox box, Random random, double margin) {
        double[] b = box.volume.bounds();
        return new Vec(b[0] - margin + random.nextDouble() * (b[3] - b[0] + 2 * margin),
                b[1] - margin + random.nextDouble() * (b[4] - b[1] + 2 * margin),
                b[2] - margin + random.nextDouble() * (b[5] - b[2] + 2 * margin));
    }

    private static Vec randomUnit(Random random) {
        double z = random.nextDouble() * 2.0D - 1.0D;
        double a = random.nextDouble() * Math.PI * 2.0D;
        double r = Math.sqrt(1.0D - z * z);
        return new Vec(r * Math.cos(a), r * Math.sin(a), z);
    }

    private static double[] profileBounds(List<ArmorBox> all) {
        double[] out = {1e9, 1e9, 1e9, -1e9, -1e9, -1e9};
        for (ArmorBox b : all) {
            double[] v = b.volume.bounds();
            for (int k = 0; k < 3; k++) {
                out[k] = Math.min(out[k], v[k]);
                out[k + 3] = Math.max(out[k + 3], v[k + 3]);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Blockbench export (mirrors the tool)

    static JsonObject geoFor(ArmorProfile p) {
        boolean mirrored = ArmorProfiles.mirrorsProfileX(p.id);
        JsonArray bones = new JsonArray();
        for (String root : List.of("armor_hull", "armor_turret", "armor_barrel")) {
            JsonObject bone = new JsonObject();
            bone.addProperty("name", root);
            bone.add("pivot", arr(0, 0, 0));
            bones.add(bone);
        }
        int n = 0;
        for (ArmorBox b : p.plates) bones.add(meshBone("plate__" + num(b.armorMm) + "mm__" + b.name, b, mirrored));
        for (ArmorBox b : p.eraBoxes) bones.add(meshBone("era__" + b.eraType + "_ke" + num(b.kineticProtectionMm)
                + "_ce" + num(b.chemicalProtectionMm) + "__" + b.name, b, mirrored));
        for (ArmorBox b : p.moduleBoxes) bones.add(meshBone("module__" + b.module + (b.unified ? "" : "-split")
                + "__" + b.name, b, mirrored));
        for (ArmorBox b : p.ammoRacks) bones.add(meshBone("ammo____" + b.name, b, mirrored));
        for (ArmorBox b : p.trackBoxes) bones.add(meshBone("track____" + b.name, b, mirrored));
        for (ArmorBox b : p.engineBoxes) bones.add(meshBone("engine____" + b.name, b, mirrored));
        for (ArmorBox b : p.sensitiveInternals) bones.add(meshBone("internal____" + b.name, b, mirrored));
        JsonObject geometry = new JsonObject();
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + p.id + "_armor");
        geometry.add("description", description);
        geometry.add("bones", bones);
        JsonArray list = new JsonArray();
        list.add(geometry);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        root.add("minecraft:geometry", list);
        return root;
    }

    private static final int[][] FACES = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};

    private static JsonObject meshBone(String name, ArmorBox box, boolean mirrored) {
        double[][] corners = new double[8][];
        for (int i = 0; i < 8; i++) {
            Vec v = new Vec(box.halfSize.x * ((i & 1) != 0 ? 1 : -1), box.halfSize.y * ((i & 2) != 0 ? 1 : -1),
                    box.halfSize.z * ((i & 4) != 0 ? 1 : -1));
            v = v.rotateX(box.rotationDeg.x).rotateY(box.rotationDeg.y).rotateZ(box.rotationDeg.z);
            Vec c = box.center.add(v);
            corners[i] = new double[] {(mirrored ? 16.0D : -16.0D) * c.x, 16.0D * c.y, 16.0D * c.z};
        }
        JsonArray positions = new JsonArray();
        JsonArray normals = new JsonArray();
        JsonArray uvs = new JsonArray();
        JsonArray polys = new JsonArray();
        int index = 0;
        for (int f = 0; f < FACES.length; f++) {
            normals.add(arr(0, 0, 0));
            JsonArray poly = new JsonArray();
            for (int corner : FACES[f]) {
                positions.add(arr(corners[corner][0], corners[corner][1], corners[corner][2]));
                uvs.add(arr2(0, 0));
                JsonArray ref = new JsonArray();
                ref.add(index); ref.add(f); ref.add(index);
                poly.add(ref);
                index++;
            }
            polys.add(poly);
        }
        JsonObject mesh = new JsonObject();
        mesh.addProperty("normalized_uvs", true);
        mesh.add("positions", positions);
        mesh.add("normals", normals);
        mesh.add("uvs", uvs);
        mesh.add("polys", polys);
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        bone.addProperty("parent", box.isBarrelFrame() ? "armor_barrel" : box.isTurretFrame() ? "armor_turret" : "armor_hull");
        bone.add("pivot", arr(0, 0, 0));
        bone.add("poly_mesh", mesh);
        return bone;
    }

    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', 'p');
    }

    private static JsonArray arr(double a, double b, double c) {
        JsonArray x = new JsonArray();
        x.add(a); x.add(b); x.add(c);
        return x;
    }

    private static JsonArray arr2(double a, double b) {
        JsonArray x = new JsonArray();
        x.add(a); x.add(b);
        return x;
    }

    private static String fmt(Vec v) {
        return String.format(Locale.ROOT, "(%.6f, %.6f, %.6f)", v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------ resources

    private static List<String> profileIds() throws Exception {
        URL dir = ArmorMeshEquivalenceTest.class.getResource("/data/berts_vehicle_pack/armor/");
        if (dir == null || !"file".equals(dir.getProtocol())) throw new AssertionError("armor profiles not on the classpath as a directory");
        String[] names = new File(dir.toURI()).list((d, n) -> n.endsWith(".json"));
        Arrays.sort(names);
        List<String> ids = new ArrayList<>();
        for (String n : names) ids.add(n.substring(0, n.length() - 5));
        return ids;
    }

    private static JsonObject read(String path) throws Exception {
        try (Reader reader = new InputStreamReader(ArmorMeshEquivalenceTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
