package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared helpers for the headless armor mesh harnesses (not a test itself). */
final class ArmorMeshTestSupport {
    static final Gson GSON = new Gson();
    // Box corner order of ArmorBoxVolume: bit 0 -> +x, bit 1 -> +y, bit 2 -> +z. Deliberately not
    // oriented: the loader must repair the winding.
    private static final int[][] FACES = {
            {0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};

    private ArmorMeshTestSupport() {
    }

    static Vec v(double x, double y, double z) {
        return new Vec(x, y, z);
    }

    /** A target with a turret pivot/yaw and an optional barrel frame; no entity behind it. */
    static final class FakeTarget implements ArmorTarget {
        final String id;
        final Vec pivot;
        final double yaw;
        final ArmorCoordinateFrame.BarrelFrame barrel;

        FakeTarget(String id, Vec pivot, double yaw, ArmorCoordinateFrame.BarrelFrame barrel) {
            this.id = id;
            this.pivot = pivot;
            this.yaw = yaw;
            this.barrel = barrel;
        }

        static FakeTarget of(String id, double yaw, double pitch) {
            Vec3 turretPivot = new Vec3(0.05D, 1.8D, 0.3D);
            Vec3 barrelPivot = new Vec3(0.0D, 0.4D, -1.1D);
            ArmorCoordinateFrame.BarrelFrame barrel = ArmorCoordinateFrame.renderedBarrelFrame(
                    turretPivot, barrelPivot, yaw, pitch, ArmorProfiles.mirrorsProfileX(id));
            Vec pivot = new Vec(-0.05D, 1.8D, -0.3D);
            return new FakeTarget(id, pivot, yaw, barrel);
        }

        @Override
        public ArmoredVehicleEntity vehicle() {
            return null;
        }

        @Override
        public Level level() {
            return null;
        }

        @Override
        public String armorProfileId() {
            return id;
        }

        @Override
        public Vec worldPointToArmorLocal(Vec3 point) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Vec3 armorLocalPointToWorld(Vec point) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Vec worldDirectionToArmorLocal(Vec3 direction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Vec turretPivot() {
            return pivot;
        }

        @Override
        public double turretFrameYaw() {
            return yaw;
        }

        @Override
        public ArmorCoordinateFrame.BarrelFrame barrelFrame() {
            return barrel;
        }
    }

    /** Box profile straight from the shipped JSON (never the mesh). */
    static ArmorProfile boxProfile(String id) {
        JsonObject root = readJsonResource("/data/berts_vehicle_pack/armor/" + id + ".json");
        if (root == null) throw new AssertionError("missing armor profile " + id);
        return ArmorProfiles.parse(id, root);
    }

    static JsonObject readJsonResource(String resource) {
        try (InputStream stream = ArmorMeshTestSupport.class.getResourceAsStream(resource)) {
            return stream == null ? null
                    : GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception exception) {
            throw new AssertionError("cannot read " + resource, exception);
        }
    }

    /** Every shipped profile id (from the classpath directory of armor profiles). */
    static List<String> profileIds() {
        URL url = ArmorMeshTestSupport.class.getResource("/data/berts_vehicle_pack/armor/");
        if (url == null || !"file".equals(url.getProtocol())) {
            throw new AssertionError("armor profiles are not on the classpath as a directory: " + url);
        }
        File directory;
        try {
            directory = new File(url.toURI());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        List<String> ids = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) ids.add(file.getName().substring(0, file.getName().length() - 5));
        }
        Collections.sort(ids);
        return ids;
    }

    /** {@code tools/armor_mesh/templates} of the repository the harness was started from, or null. */
    static Path templateDirectory() {
        String override = System.getProperty("bvp.armorMeshTemplates", System.getenv("BVP_ARMOR_MESH_TEMPLATES"));
        if (override != null && Files.isDirectory(Path.of(override))) return Path.of(override);
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("tools/armor_mesh/templates");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        return null;
    }

    static JsonObject readJsonFile(Path path) {
        try {
            return GSON.fromJson(Files.readString(path), JsonObject.class);
        } catch (Exception exception) {
            throw new AssertionError("cannot read " + path, exception);
        }
    }

    /**
     * In-memory equivalent of tools/armor_mesh/export_boxes.py: every box as a closed 6-quad mesh
     * in visual-model geo units, named {@code <kind>__<param>__<name>} under the frame roots.
     */
    static JsonObject exportTemplate(ArmorProfile profile) {
        boolean mirrored = ArmorProfiles.mirrorsProfileX(profile.id);
        JsonArray bones = new JsonArray();
        bones.add(bone("armor_hull", null));
        bones.add(bone("armor_turret", "armor_hull"));
        bones.add(bone("armor_barrel", "armor_turret"));
        addBoxes(bones, "plate", profile.plates, mirrored);
        addBoxes(bones, "era", profile.eraBoxes, mirrored);
        addBoxes(bones, "engine", profile.engineBoxes, mirrored);
        addBoxes(bones, "internal", profile.sensitiveInternals, mirrored);
        addBoxes(bones, "ammo", profile.ammoRacks, mirrored);
        addBoxes(bones, "module", profile.moduleBoxes, mirrored);
        addBoxes(bones, "track", profile.trackBoxes, mirrored);
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry." + profile.id + "_armor");
        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add("bones", bones);
        JsonArray geometries = new JsonArray();
        geometries.add(geometry);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        root.add("minecraft:geometry", geometries);
        return root;
    }

    static String boneName(String kind, ArmorBox box) {
        return switch (kind) {
            case "plate" -> "plate__" + number(box.armorMm) + "mm__" + box.name;
            case "era" -> "era__" + box.eraType + "_ke" + number(box.kineticProtectionMm)
                    + "_ce" + number(box.chemicalProtectionMm) + "__" + box.name;
            case "module" -> "module__" + box.module + (box.unified ? "" : "-split") + "__" + box.name;
            default -> kind + "____" + box.name;
        };
    }

    private static void addBoxes(JsonArray bones, String kind, List<ArmorBox> boxes, boolean mirrored) {
        for (ArmorBox box : boxes) {
            JsonObject bone = bone(boneName(kind, box), "armor_" + box.frame);
            JsonArray positions = new JsonArray();
            double[] corner = new double[3];
            for (int i = 0; i < 8; i++) {
                box.volume.vertex(i, corner);
                JsonArray position = new JsonArray();
                position.add((mirrored ? 1.0D : -1.0D) * corner[0] * 16.0D);
                position.add(corner[1] * 16.0D);
                position.add(corner[2] * 16.0D);
                positions.add(position);
            }
            JsonArray polys = new JsonArray();
            for (int[] face : FACES) {
                JsonArray poly = new JsonArray();
                for (int k = 0; k < 4; k++) {
                    JsonArray vertex = new JsonArray();
                    vertex.add(face[k]);
                    vertex.add(0);
                    vertex.add(k);
                    poly.add(vertex);
                }
                polys.add(poly);
            }
            JsonObject mesh = new JsonObject();
            mesh.addProperty("normalized_uvs", true);
            mesh.add("positions", positions);
            mesh.add("polys", polys);
            bone.add("poly_mesh", mesh);
            bones.add(bone);
        }
    }

    private static JsonObject bone(String name, String parent) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) bone.addProperty("parent", parent);
        JsonArray pivot = new JsonArray();
        pivot.add(0);
        pivot.add(0);
        pivot.add(0);
        bone.add("pivot", pivot);
        return bone;
    }

    static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    static void near(double actual, double expected, double tolerance, String label) {
        if (!(Math.abs(actual - expected) <= tolerance)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    static void nearVec(Vec actual, Vec expected, double tolerance, String label) {
        if (actual == null || expected == null) throw new AssertionError(label + ": null vector");
        near(actual.x, expected.x, tolerance, label + ".x");
        near(actual.y, expected.y, tolerance, label + ".y");
        near(actual.z, expected.z, tolerance, label + ".z");
    }

    /** A volume from a triangle soup given in blocks. */
    static ArmorMeshVolume mesh(double[] soup, List<String> warnings) {
        return ArmorMeshVolume.build(soup, soup.length / 9, "test", warnings);
    }

    /** Closed box soup (12 triangles) with outward winding, optionally with some faces flipped. */
    static double[] boxSoup(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                            boolean scrambleWinding) {
        double[][] c = new double[8][];
        for (int i = 0; i < 8; i++) {
            c[i] = new double[] {(i & 1) == 0 ? minX : maxX, (i & 2) == 0 ? minY : maxY, (i & 4) == 0 ? minZ : maxZ};
        }
        int[][] quads = {{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
        double[] soup = new double[12 * 9];
        int t = 0;
        for (int q = 0; q < quads.length; q++) {
            int[] quad = quads[q];
            int[][] tris = {{quad[0], quad[1], quad[2]}, {quad[0], quad[2], quad[3]}};
            for (int[] tri : tris) {
                boolean flip = scrambleWinding && (q + t) % 3 == 0;
                int[] order = flip ? new int[] {tri[0], tri[2], tri[1]} : tri;
                for (int k = 0; k < 3; k++) System.arraycopy(c[order[k]], 0, soup, t * 9 + k * 3, 3);
                t++;
            }
        }
        return soup;
    }

    static double[] concat(double[]... parts) {
        int length = 0;
        for (double[] part : parts) length += part.length;
        double[] out = new double[length];
        int offset = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }
}
