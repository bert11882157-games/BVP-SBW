package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Blockbench Bedrock geometry file ({@code .geo.json}) whose bones describe armor volumes.
 * See {@code docs/ARMOR_MESH.md} for the authoring rules.
 *
 * <ul>
 *   <li>A bone named {@code <kind>__<param>__<name>} is one volume. Its own mesh elements
 *   ({@code poly_mesh}), cubes and every unnamed child bone form the volume (their union).</li>
 *   <li>The nearest ancestor named {@code hull}, {@code turret} or {@code barrel} (optionally
 *   prefixed {@code armor_}, optionally followed by Blockbench's de-duplication digits) selects the
 *   volume's frame; a volume without one is a hull volume.</li>
 *   <li>Positions are geo units (1/16 block) in the same frame as the vehicle's visual model.
 *   Armor-profile coordinates are {@code (-x, y, z) / 16}, or {@code (x, y, z) / 16} for
 *   X-mirrored profiles.</li>
 *   <li>Bone and cube rotations follow Blockbench's Bedrock export: stored X and Y angles are
 *   negated relative to what Blockbench shows, rotation order Z(Y(X(v))), about the pivot.
 *   Rotations on frame bones are ignored because armor is authored at the rest pose.</li>
 * </ul>
 *
 * <p>Uses only Gson and plain Java, so it is safe on the dedicated server.</p>
 */
final class ArmorMeshLoader {
    private static final Pattern FRAME_NAME = Pattern.compile("^(?:armor[_.-]?)?(hull|turret|barrel|barell)\\d*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern THICKNESS = Pattern.compile("^(\\d+(?:[.p]\\d+)?)(?:mm)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KINETIC = Pattern.compile("^ke(\\d+(?:[.p]\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHEMICAL = Pattern.compile("^ce(\\d+(?:[.p]\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Kind> KINDS = Map.of(
            "plate", Kind.PLATE, "armor", Kind.PLATE,
            "era", Kind.ERA,
            "engine", Kind.ENGINE,
            "ammo", Kind.AMMO,
            "module", Kind.MODULE,
            "track", Kind.TRACK,
            "internal", Kind.INTERNAL);
    /** Protection defaults by ERA type (from the shipped box profiles); unknown types use the box defaults. */
    private static final Map<String, double[]> ERA_DEFAULTS = Map.of(
            "kontakt1", new double[] {25.0D, 400.0D},
            "kontakt5", new double[] {120.0D, 450.0D},
            "relict", new double[] {200.0D, 600.0D});
    private static final double[] ERA_FALLBACK = {25.0D, 400.0D};

    enum Kind { PLATE, ERA, ENGINE, AMMO, MODULE, TRACK, INTERNAL }

    enum Frame {
        HULL("hull"), TURRET("turret"), BARREL("barrel");

        final String id;

        Frame(String id) {
            this.id = id;
        }
    }

    private ArmorMeshLoader() {
    }

    /** Parsed volume bone name. {@code error} is non-null when the kind is known but the rest is not. */
    record VolumeName(Kind kind, String name, double armorMm, String eraType, double kineticMm,
                      double chemicalMm, String module, boolean unified, String error) {
    }

    /** Loaded volumes, grouped like the box profile lists. */
    static final class Result {
        final List<ArmorBox> plates = new ArrayList<>();
        final List<ArmorBox> era = new ArrayList<>();
        final List<ArmorBox> engines = new ArrayList<>();
        final List<ArmorBox> ammoRacks = new ArrayList<>();
        final List<ArmorBox> modules = new ArrayList<>();
        final List<ArmorBox> tracks = new ArrayList<>();
        final List<ArmorBox> internals = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int volumes;
        int triangles;
    }

    /** Frame selected by a bone name, or null when the name is not a frame name. */
    static Frame frameOf(String boneName) {
        if (boneName == null) return null;
        Matcher matcher = FRAME_NAME.matcher(boneName.trim());
        if (!matcher.matches()) return null;
        String frame = matcher.group(1).toLowerCase(Locale.ROOT);
        return switch (frame) {
            case "turret" -> Frame.TURRET;
            case "barrel", "barell" -> Frame.BARREL;
            default -> Frame.HULL;
        };
    }

    /** Parses {@code <kind>__<param>__<name>}; null when the text before the first {@code __} is no kind. */
    static VolumeName parseName(String bone) {
        if (bone == null) return null;
        int first = bone.indexOf("__");
        if (first <= 0) return null;
        Kind kind = KINDS.get(bone.substring(0, first).toLowerCase(Locale.ROOT));
        if (kind == null) return null;
        String rest = bone.substring(first + 2);
        int second = rest.indexOf("__");
        String param = second < 0 ? "" : rest.substring(0, second);
        String name = second < 0 ? rest : rest.substring(second + 2);
        if (name.isBlank()) {
            return error(kind, "volume name is empty; use " + kind.name().toLowerCase(Locale.ROOT)
                    + "__<param>__<name>");
        }
        return switch (kind) {
            case PLATE -> {
                Matcher matcher = THICKNESS.matcher(param);
                if (second < 0 || !matcher.matches()) {
                    yield error(kind, "plate needs a thickness, e.g. plate__80mm__" + name);
                }
                yield new VolumeName(kind, name, number(matcher.group(1)), "", 0.0D, 0.0D, "", true, null);
            }
            case ERA -> {
                String type = "";
                double kinetic = Double.NaN;
                double chemical = Double.NaN;
                StringBuilder typeText = new StringBuilder();
                for (String token : param.split("_")) {
                    if (token.isEmpty()) continue;
                    Matcher ke = KINETIC.matcher(token);
                    Matcher ce = CHEMICAL.matcher(token);
                    if (ke.matches()) {
                        kinetic = number(ke.group(1));
                    } else if (ce.matches()) {
                        chemical = number(ce.group(1));
                    } else {
                        typeText.append(token);
                    }
                }
                type = typeText.length() == 0 ? "kontakt1" : typeText.toString();
                double[] defaults = ERA_DEFAULTS.getOrDefault(normalizeEraType(type), ERA_FALLBACK);
                yield new VolumeName(kind, name, 0.0D, type, Double.isNaN(kinetic) ? defaults[0] : kinetic,
                        Double.isNaN(chemical) ? defaults[1] : chemical, "", true, null);
            }
            case MODULE -> {
                String module = param;
                boolean unified = true;
                if (module.toLowerCase(Locale.ROOT).endsWith("-split")) {
                    module = module.substring(0, module.length() - "-split".length());
                    unified = false;
                }
                yield new VolumeName(kind, name, 0.0D, "", 0.0D, 0.0D, module, unified, null);
            }
            default -> new VolumeName(kind, name, 0.0D, "", 0.0D, 0.0D, "", true, null);
        };
    }

    private static VolumeName error(Kind kind, String message) {
        return new VolumeName(kind, "", 0.0D, "", 0.0D, 0.0D, "", true, message);
    }

    private static double number(String text) {
        return Double.parseDouble(text.toLowerCase(Locale.ROOT).replace('p', '.'));
    }

    private static String normalizeEraType(String type) {
        return type.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** First geometry object of a Bedrock geometry file (1.12+ or the legacy 1.8/1.10 layout). */
    static JsonObject geometry(JsonObject root) {
        if (root == null) return null;
        JsonElement modern = root.get("minecraft:geometry");
        if (modern != null && modern.isJsonArray() && modern.getAsJsonArray().size() > 0
                && modern.getAsJsonArray().get(0).isJsonObject()) {
            return modern.getAsJsonArray().get(0).getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry.getKey().startsWith("geometry.") && entry.getValue().isJsonObject()) {
                return entry.getValue().getAsJsonObject();
            }
        }
        return null;
    }

    static Result load(String label, JsonObject root, boolean mirrorProfileX) {
        Result result = new Result();
        JsonObject geometry = geometry(root);
        if (geometry == null || !geometry.has("bones") || !geometry.get("bones").isJsonArray()) {
            result.warnings.add(label + ": no minecraft:geometry bones");
            return result;
        }
        List<JsonObject> bones = new ArrayList<>();
        Map<String, JsonObject> byName = new HashMap<>();
        for (JsonElement element : geometry.getAsJsonArray("bones")) {
            if (!element.isJsonObject()) continue;
            JsonObject bone = element.getAsJsonObject();
            String name = string(bone, "name");
            if (name == null) continue;
            if (byName.putIfAbsent(name, bone) != null) {
                result.warnings.add(label + ": duplicate bone name '" + name + "'; only the first is used as a parent");
            }
            bones.add(bone);
        }

        Map<String, double[]> transforms = new HashMap<>();
        Map<String, Accumulator> volumes = new LinkedHashMap<>();
        Set<String> invalid = new HashSet<>();
        for (JsonObject bone : bones) {
            String name = string(bone, "name");
            JsonObject owner = volumeOwner(bone, byName);
            boolean hasGeometry = bone.has("poly_mesh") || (bone.has("cubes") && bone.get("cubes").isJsonArray()
                    && bone.getAsJsonArray("cubes").size() > 0);
            if (owner == null) {
                VolumeName parsed = parseName(name);
                if (parsed != null && parsed.error() != null && invalid.add(name)) {
                    result.warnings.add(label + ": bone '" + name + "' ignored: " + parsed.error());
                }
                Frame frame = frameOf(name);
                if (hasGeometry && frame != null && name.toLowerCase(Locale.ROOT).startsWith("armor")) {
                    result.warnings.add(label + ": geometry directly in frame bone '" + name
                            + "' is ignored; put it in a bone named like plate__80mm__<name>");
                }
                if (frame != null && hasRotation(bone) && name.toLowerCase(Locale.ROOT).startsWith("armor")) {
                    result.warnings.add(label + ": rotation on frame bone '" + name
                            + "' is ignored (armor is authored at the rest pose)");
                }
                continue;
            }
            String ownerName = string(owner, "name");
            Accumulator accumulator = volumes.get(ownerName);
            if (accumulator == null) {
                VolumeName parsed = parseName(ownerName);
                Frame frame = frameAbove(owner, byName);
                if (frame == null) {
                    result.warnings.add(label + ": volume '" + ownerName
                            + "' has no hull/turret/barrel ancestor; using the hull frame");
                    frame = Frame.HULL;
                }
                accumulator = new Accumulator(ownerName, parsed, frame);
                volumes.put(ownerName, accumulator);
            }
            if (!hasGeometry) continue;
            double[] matrix = boneTransform(bone, byName, transforms, new HashSet<>());
            if (matrix == null) {
                result.warnings.add(label + ": bone '" + name + "' has a cyclic parent chain; skipped");
                continue;
            }
            appendPolyMesh(bone, matrix, accumulator, label, result.warnings);
            appendCubes(bone, matrix, accumulator);
        }

        Set<String> usedNames = new HashSet<>();
        for (Accumulator accumulator : volumes.values()) {
            VolumeName parsed = accumulator.name;
            String volumeLabel = label + " volume '" + accumulator.bone + "'";
            if (accumulator.triangles == 0) {
                result.warnings.add(volumeLabel + ": no geometry; skipped");
                continue;
            }
            double[] soup = accumulator.toArmorLocal(mirrorProfileX);
            ArmorMeshVolume volume = ArmorMeshVolume.build(soup, accumulator.triangles, volumeLabel, result.warnings);
            if (volume == null) continue;
            if (!usedNames.add(parsed.name().toLowerCase(Locale.ROOT))) {
                result.warnings.add(volumeLabel + ": volume name '" + parsed.name()
                        + "' is used twice; hit highlights and ERA spent state are shared by name");
            }
            ArmorBox box = ArmorBox.mesh(parsed.name(), parsed.armorMm(), volume, accumulator.frame.id,
                    parsed.module(), parsed.unified(), parsed.eraType(), parsed.kineticMm(), parsed.chemicalMm());
            result.volumes++;
            result.triangles += volume.triangleCount();
            switch (parsed.kind()) {
                case PLATE -> result.plates.add(box);
                case ERA -> result.era.add(box);
                case ENGINE -> result.engines.add(box);
                case AMMO -> result.ammoRacks.add(box);
                case MODULE -> result.modules.add(box);
                case TRACK -> result.tracks.add(box);
                case INTERNAL -> {
                    if (ArmorProfiles.isEngineBoxName(parsed.name())) result.engines.add(box);
                    else result.internals.add(box);
                }
            }
        }
        return result;
    }

    /** Nearest ancestor-or-self whose name is a valid volume name; null when none. */
    private static JsonObject volumeOwner(JsonObject bone, Map<String, JsonObject> byName) {
        Set<String> visited = new HashSet<>();
        JsonObject current = bone;
        while (current != null) {
            String name = string(current, "name");
            if (name == null || !visited.add(name)) return null;
            VolumeName parsed = parseName(name);
            if (parsed != null) return parsed.error() == null ? current : null;
            if (frameOf(name) != null) return null;
            String parent = string(current, "parent");
            current = parent == null ? null : byName.get(parent);
        }
        return null;
    }

    /** Frame of the nearest strict ancestor with a frame name. */
    private static Frame frameAbove(JsonObject bone, Map<String, JsonObject> byName) {
        Set<String> visited = new HashSet<>();
        String parent = string(bone, "parent");
        while (parent != null && visited.add(parent)) {
            Frame frame = frameOf(parent);
            if (frame != null) return frame;
            JsonObject next = byName.get(parent);
            parent = next == null ? null : string(next, "parent");
        }
        return null;
    }

    private static boolean hasRotation(JsonObject object) {
        double[] rotation = vector(object, "rotation");
        return rotation[0] != 0.0D || rotation[1] != 0.0D || rotation[2] != 0.0D;
    }

    /** Raw geo-space 3x4 affine transform of a bone (parent chain applied). */
    static double[] boneTransform(JsonObject bone, Map<String, JsonObject> byName, Map<String, double[]> cache,
                                  Set<String> visiting) {
        String name = string(bone, "name");
        double[] cached = cache.get(name);
        if (cached != null) return cached;
        if (!visiting.add(name)) return null;
        String parentName = string(bone, "parent");
        JsonObject parent = parentName == null ? null : byName.get(parentName);
        double[] base = parent == null ? identity() : boneTransform(parent, byName, cache, visiting);
        if (base == null) return null;
        double[] local = frameOf(name) != null
                ? identity()
                : rotationAbout(vector(bone, "pivot"), vector(bone, "rotation"));
        double[] result = multiply(base, local);
        cache.put(name, result);
        return result;
    }

    /**
     * {@code T(pivot) * Rz(-rz) * Ry(ry) * Rx(-rx) * T(-pivot)} in raw geo space: Blockbench stores
     * Bedrock angles with X and Y negated and applies X, then Y, then Z in its own (X-mirrored)
     * space, which is this rotation once expressed in the file's coordinates.
     */
    static double[] rotationAbout(double[] pivot, double[] rotationDeg) {
        double rx = Math.toRadians(-rotationDeg[0]);
        double ry = Math.toRadians(rotationDeg[1]);
        double rz = Math.toRadians(-rotationDeg[2]);
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double cz = Math.cos(rz), sz = Math.sin(rz);
        // R = Rz * Ry * Rx
        double r00 = cz * cy, r01 = cz * sy * sx - sz * cx, r02 = cz * sy * cx + sz * sx;
        double r10 = sz * cy, r11 = sz * sy * sx + cz * cx, r12 = sz * sy * cx - cz * sx;
        double r20 = -sy, r21 = cy * sx, r22 = cy * cx;
        double px = pivot[0], py = pivot[1], pz = pivot[2];
        double tx = px - (r00 * px + r01 * py + r02 * pz);
        double ty = py - (r10 * px + r11 * py + r12 * pz);
        double tz = pz - (r20 * px + r21 * py + r22 * pz);
        return new double[] {r00, r01, r02, tx, r10, r11, r12, ty, r20, r21, r22, tz};
    }

    static double[] identity() {
        return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
    }

    static double[] multiply(double[] a, double[] b) {
        double[] out = new double[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 4; col++) {
                double value = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col];
                if (col == 3) value += a[row * 4 + 3];
                out[row * 4 + col] = value;
            }
        }
        return out;
    }

    static void apply(double[] m, double x, double y, double z, double[] out, int o) {
        out[o] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[o + 1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[o + 2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    private static void appendPolyMesh(JsonObject bone, double[] matrix, Accumulator accumulator, String label,
                                       List<String> warnings) {
        JsonElement meshElement = bone.get("poly_mesh");
        if (meshElement == null || !meshElement.isJsonObject()) return;
        JsonObject mesh = meshElement.getAsJsonObject();
        JsonArray positionArray = mesh.has("positions") && mesh.get("positions").isJsonArray()
                ? mesh.getAsJsonArray("positions") : new JsonArray();
        double[] positions = new double[positionArray.size() * 3];
        for (int i = 0; i < positionArray.size(); i++) {
            JsonArray p = positionArray.get(i).getAsJsonArray();
            apply(matrix, p.get(0).getAsDouble(), p.get(1).getAsDouble(), p.get(2).getAsDouble(), positions, i * 3);
        }
        int positionCount = positionArray.size();
        JsonElement polys = mesh.get("polys");
        List<int[]> polygons = new ArrayList<>();
        if (polys != null && polys.isJsonPrimitive()) {
            String layout = polys.getAsString();
            int size = "quad_list".equals(layout) ? 4 : "tri_list".equals(layout) ? 3 : 0;
            for (int start = 0; size > 0 && start + size <= positionCount; start += size) {
                int[] polygon = new int[size];
                for (int i = 0; i < size; i++) polygon[i] = start + i;
                polygons.add(polygon);
            }
        } else if (polys != null && polys.isJsonArray()) {
            for (JsonElement polyElement : polys.getAsJsonArray()) {
                if (!polyElement.isJsonArray()) continue;
                JsonArray poly = polyElement.getAsJsonArray();
                int[] polygon = new int[poly.size()];
                for (int i = 0; i < poly.size(); i++) {
                    JsonElement vertex = poly.get(i);
                    polygon[i] = vertex.isJsonArray() ? vertex.getAsJsonArray().get(0).getAsInt() : vertex.getAsInt();
                }
                polygons.add(polygon);
            }
        }
        int badIndices = 0;
        for (int[] polygon : polygons) {
            // Blockbench pads triangles to quads by repeating the first vertex; drop repeats.
            int[] unique = new int[polygon.length];
            int count = 0;
            for (int index : polygon) {
                boolean seen = false;
                for (int j = 0; j < count; j++) seen |= unique[j] == index;
                if (!seen) unique[count++] = index;
            }
            if (count < 3) continue;
            boolean valid = true;
            for (int j = 0; j < count; j++) valid &= unique[j] >= 0 && unique[j] < positionCount;
            if (!valid) {
                badIndices++;
                continue;
            }
            for (int j = 1; j + 1 < count; j++) {
                accumulator.add(positions, unique[0], unique[j], unique[j + 1]);
            }
        }
        if (badIndices > 0) {
            warnings.add(label + ": bone '" + string(bone, "name") + "' has " + badIndices
                    + " polygon(s) with out-of-range position indices");
        }
    }

    private static final int[][] CUBE_FACES = {
            {0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};

    private static void appendCubes(JsonObject bone, double[] matrix, Accumulator accumulator) {
        JsonElement cubes = bone.get("cubes");
        if (cubes == null || !cubes.isJsonArray()) return;
        for (JsonElement element : cubes.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject cube = element.getAsJsonObject();
            double[] origin = vector(cube, "origin");
            double[] size = vector(cube, "size");
            double inflate = cube.has("inflate") ? cube.get("inflate").getAsDouble() : 0.0D;
            double[] cubeMatrix = multiply(matrix, rotationAbout(vector(cube, "pivot"), vector(cube, "rotation")));
            double[] corners = new double[24];
            for (int corner = 0; corner < 8; corner++) {
                apply(cubeMatrix,
                        (corner & 1) == 0 ? origin[0] - inflate : origin[0] + size[0] + inflate,
                        (corner & 2) == 0 ? origin[1] - inflate : origin[1] + size[1] + inflate,
                        (corner & 4) == 0 ? origin[2] - inflate : origin[2] + size[2] + inflate,
                        corners, corner * 3);
            }
            for (int[] face : CUBE_FACES) {
                accumulator.add(corners, face[0], face[1], face[2]);
                accumulator.add(corners, face[0], face[2], face[3]);
            }
        }
    }

    private static double[] vector(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() < 3) {
            return new double[3];
        }
        JsonArray array = element.getAsJsonArray();
        return new double[] {array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    /** Raw geo-space triangles of one volume. */
    private static final class Accumulator {
        final String bone;
        final VolumeName name;
        final Frame frame;
        double[] soup = new double[9 * 16];
        int triangles;

        Accumulator(String bone, VolumeName name, Frame frame) {
            this.bone = bone;
            this.name = name;
            this.frame = frame;
        }

        void add(double[] points, int a, int b, int c) {
            if (soup.length < (triangles + 1) * 9) {
                soup = Arrays.copyOf(soup, soup.length * 2);
            }
            int o = triangles * 9;
            System.arraycopy(points, a * 3, soup, o, 3);
            System.arraycopy(points, b * 3, soup, o + 3, 3);
            System.arraycopy(points, c * 3, soup, o + 6, 3);
            triangles++;
        }

        /** Geo units to armor-profile blocks: divide by 16 and negate X unless the profile is X-mirrored. */
        double[] toArmorLocal(boolean mirrorProfileX) {
            double[] out = new double[triangles * 9];
            double sign = mirrorProfileX ? 1.0D : -1.0D;
            for (int i = 0; i < triangles * 9; i += 3) {
                out[i] = sign * soup[i] / 16.0D;
                out[i + 1] = soup[i + 1] / 16.0D;
                out[i + 2] = soup[i + 2] / 16.0D;
            }
            return out;
        }
    }
}
