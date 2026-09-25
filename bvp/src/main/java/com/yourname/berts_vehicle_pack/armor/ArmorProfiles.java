package com.yourname.berts_vehicle_pack.armor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Armor profiles: profile-level settings from {@code data/berts_vehicle_pack/armor/<id>.json} and
 * the armor volumes. Volumes come from the profile's box lists, or, when
 * {@code data/berts_vehicle_pack/armor_mesh/<id>.geo.json} exists (and the profile does not set
 * {@code "armor_mesh": false}), from that Blockbench mesh file, which then replaces every box list.
 * See {@code docs/ARMOR_MESH.md}.
 */
public final class ArmorProfiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final ConcurrentHashMap<String, CompletableFuture<ArmorProfile>> CACHE = new ConcurrentHashMap<>();
    private static final String ARMOR_ROOT = "/data/" + BertsVehiclePack.MODID + "/armor/";
    private static final String MESH_ROOT = "/data/" + BertsVehiclePack.MODID + "/armor_mesh/";
    private static final Pattern RESOURCE_ID = Pattern.compile("^[a-z0-9_]+$");
    private static final int MAX_LOGGED_MESH_WARNINGS = 40;
    private static final double DEFAULT_AP_PENETRATION_MM = 500.0D;
    private static final double DEFAULT_CHEMICAL_PENETRATION_MM = 700.0D;
    private static final double DEFAULT_FALLBACK_INCOMING_PENETRATION_MM = 500.0D;
    private static final double DEFAULT_IMPACT_TOLERANCE = 0.25D;
    private static final double DEFAULT_INTERNAL_RAY_LENGTH = 10.0D;
    private static final boolean DEFAULT_UNBOXED_HITS_PENETRATE = true;
    private static volatile ExecutorService prefetchExecutor;

    private ArmorProfiles() {
    }

    public static ArmorProfile get(String id) {
        if (id == null) {
            return ArmorProfile.empty(null);
        }
        CompletableFuture<ArmorProfile> future = CACHE.get(id);
        if (future == null) {
            CompletableFuture<ArmorProfile> mine = new CompletableFuture<>();
            future = CACHE.putIfAbsent(id, mine);
            if (future == null) {
                future = mine;
                complete(mine, id);
            }
        }
        return future.join();
    }

    /**
     * Starts loading a profile on a background thread, so parsing a large armor mesh never lands
     * on the game or render thread. {@link #get} waits for a load already in progress.
     */
    public static void prefetch(String id) {
        if (id == null || CACHE.containsKey(id)) {
            return;
        }
        CompletableFuture<ArmorProfile> mine = new CompletableFuture<>();
        if (CACHE.putIfAbsent(id, mine) != null) {
            return;
        }
        try {
            executor().execute(() -> complete(mine, id));
        } catch (RuntimeException rejected) {
            complete(mine, id);
        }
    }

    /** Armor profiles authored in hit-local space while the rendered model is mirrored on X. */
    public static boolean mirrorsProfileX(String id) {
        return "t72a".equals(id) || "t72b".equals(id);
    }

    private static ExecutorService executor() {
        ExecutorService executor = prefetchExecutor;
        if (executor == null) {
            synchronized (ArmorProfiles.class) {
                executor = prefetchExecutor;
                if (executor == null) {
                    executor = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "BVP armor profile loader");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.MIN_PRIORITY);
                        return thread;
                    });
                    prefetchExecutor = executor;
                }
            }
        }
        return executor;
    }

    private static void complete(CompletableFuture<ArmorProfile> future, String id) {
        ArmorProfile profile;
        try {
            profile = load(id);
        } catch (Throwable failure) {
            LOGGER.warn("[BVP Armor] Failed to load armor profile '{}'; using empty fallback.", id, failure);
            profile = ArmorProfile.empty(id);
        }
        future.complete(profile);
    }

    private static ArmorProfile load(String id) {
        JsonObject root;
        try {
            root = readJson(ARMOR_ROOT + id + ".json");
        } catch (Exception exception) {
            LOGGER.warn("[BVP Armor] Failed to load armor profile '{}'; using empty fallback.", id, exception);
            return ArmorProfile.empty(id);
        }
        String meshId = meshResourceId(id, root);
        JsonObject geo = null;
        if (meshId != null) {
            try {
                geo = readJson(MESH_ROOT + meshId + ".geo.json");
            } catch (Exception exception) {
                LOGGER.error("[BVP Armor] Armor mesh '{}' for profile '{}' could not be read; using the box volumes.",
                        meshId, id, exception);
            }
            if (geo == null && root != null && root.has("armor_mesh") && root.get("armor_mesh").isJsonPrimitive()
                    && root.getAsJsonPrimitive("armor_mesh").isString()) {
                LOGGER.warn("[BVP Armor] Profile '{}' names armor mesh '{}', which does not exist.", id, meshId);
            }
        }
        if (root == null && geo == null) {
            LOGGER.warn("[BVP Armor] Missing armor profile '{}'; using empty fallback.", id);
            return ArmorProfile.empty(id);
        }
        ArmorProfile boxes;
        try {
            boxes = parse(id, root == null ? new JsonObject() : root);
        } catch (Exception exception) {
            LOGGER.warn("[BVP Armor] Failed to load armor profile '{}'; using empty fallback.", id, exception);
            return ArmorProfile.empty(id);
        }
        if (geo == null) {
            return boxes;
        }
        try {
            return withMesh(boxes, geo, "armor_mesh/" + meshId + ".geo.json");
        } catch (Exception exception) {
            LOGGER.error("[BVP Armor] Armor mesh '{}' for profile '{}' failed to load; using the box volumes.",
                    meshId, id, exception);
            return boxes;
        }
    }

    private static JsonObject readJson(String resource) throws Exception {
        try (InputStream stream = ArmorProfiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            return GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    /** Mesh resource id for a profile: its own id, another id named by "armor_mesh", or null when disabled. */
    static String meshResourceId(String id, JsonObject root) {
        JsonElement flag = root == null ? null : root.get("armor_mesh");
        if (flag == null || flag.isJsonNull()) {
            return RESOURCE_ID.matcher(id).matches() ? id : null;
        }
        if (flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()) {
            return flag.getAsBoolean() && RESOURCE_ID.matcher(id).matches() ? id : null;
        }
        if (flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isString()) {
            String other = flag.getAsString().trim();
            if (RESOURCE_ID.matcher(other).matches()) {
                return other;
            }
        }
        LOGGER.warn("[BVP Armor] Profile '{}' has an invalid \"armor_mesh\" value {}; expected false, true or a"
                + " profile id.", id, flag);
        return RESOURCE_ID.matcher(id).matches() ? id : null;
    }

    /** Box profile parsed from its JSON settings and box lists. */
    static ArmorProfile parse(String id, JsonObject root) {
        double apPenetrationMm = number(root, "ap_penetration_mm", DEFAULT_AP_PENETRATION_MM);
        double chemicalPenetrationMm = number(root, "chemical_penetration_mm",
                DEFAULT_CHEMICAL_PENETRATION_MM);
        double fallbackIncomingPenetrationMm = number(root, "fallback_incoming_penetration_mm",
                DEFAULT_FALLBACK_INCOMING_PENETRATION_MM);
        double impactTolerance = number(root, "impact_tolerance", DEFAULT_IMPACT_TOLERANCE);
        double internalRayLength = number(root, "internal_ray_length", DEFAULT_INTERNAL_RAY_LENGTH);
        boolean unboxedHitsPenetrate = bool(root, "unboxed_hits_penetrate",
                DEFAULT_UNBOXED_HITS_PENETRATE);
        boolean strictArmorGate = bool(root, "strict_armor_gate", defaultStrictArmorGate(id));
        boolean atgmTandemWarhead = bool(root, "atgm_tandem", false);
        List<ArmorBox> plates = readBoxes(root.getAsJsonArray("plates"), true);
        List<ArmorBox> rawInternals = readBoxes(root.getAsJsonArray("sensitive_internals"), false);
        List<ArmorBox> internals = new ArrayList<>();
        List<ArmorBox> engines = new ArrayList<>(readBoxes(root.getAsJsonArray("engines"), false));
        for (ArmorBox internal : rawInternals) {
            if (isEngineBoxName(internal.name)) {
                engines.add(internal);
            } else {
                internals.add(internal);
            }
        }
        List<ArmorBox> ammoRacks = readBoxes(root.getAsJsonArray("ammo_racks"), false);
        List<ArmorBox> tracks = readBoxes(root.getAsJsonArray("tracks"), false);
        List<ArmorBox> modules = readBoxes(root.getAsJsonArray("modules"), false);
        JsonArray eraArray = root.has("explosive_reactive_armor")
                ? root.getAsJsonArray("explosive_reactive_armor")
                : root.getAsJsonArray("era");
        List<ArmorBox> eraBoxes = readEraBoxes(eraArray);
        return new ArmorProfile(id, apPenetrationMm, chemicalPenetrationMm, fallbackIncomingPenetrationMm,
                impactTolerance, internalRayLength, unboxedHitsPenetrate, strictArmorGate, atgmTandemWarhead,
                plates, internals, engines, ammoRacks, tracks, modules, eraBoxes, null, List.of());
    }

    /**
     * The profile's settings with every volume list replaced by the volumes of a Blockbench armor
     * mesh. Returns {@code boxes} unchanged (with a warning) when the mesh defines no volume.
     */
    static ArmorProfile withMesh(ArmorProfile boxes, JsonObject geo, String source) {
        ArmorMeshLoader.Result result = ArmorMeshLoader.load(boxes.id + " " + source, geo,
                mirrorsProfileX(boxes.id));
        int logged = 0;
        for (String warning : result.warnings) {
            if (logged++ >= MAX_LOGGED_MESH_WARNINGS) {
                LOGGER.warn("[BVP Armor] ... {} more armor mesh warning(s) for '{}'.",
                        result.warnings.size() - MAX_LOGGED_MESH_WARNINGS, boxes.id);
                break;
            }
            LOGGER.warn("[BVP Armor] {}", warning);
        }
        if (result.volumes == 0) {
            LOGGER.warn("[BVP Armor] Armor mesh {} for profile '{}' defines no volumes; using the box volumes.",
                    source, boxes.id);
            return boxes;
        }
        LOGGER.info("[BVP Armor] Profile '{}' uses armor mesh {}: {} volumes ({} plates, {} ERA, {} engines,"
                        + " {} ammo, {} modules, {} tracks, {} internals), {} triangles, {} warning(s).",
                boxes.id, source, result.volumes, result.plates.size(), result.era.size(), result.engines.size(),
                result.ammoRacks.size(), result.modules.size(), result.tracks.size(), result.internals.size(),
                result.triangles, result.warnings.size());
        return new ArmorProfile(boxes.id, boxes.apPenetrationMm, boxes.chemicalPenetrationMm,
                boxes.fallbackIncomingPenetrationMm, boxes.impactTolerance, boxes.internalRayLength,
                boxes.unboxedHitsPenetrate, boxes.strictArmorGate, boxes.atgmTandemWarhead,
                result.plates, result.internals, result.engines, result.ammoRacks, result.tracks, result.modules,
                result.era, source, result.warnings);
    }

    static boolean isEngineBoxName(String name) {
        String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("engine_") || normalized.contains("_engine_")
                || normalized.startsWith("motor_") || normalized.contains("_motor_")
                || normalized.startsWith("powerpack_") || normalized.contains("_powerpack_");
    }

    private static boolean defaultStrictArmorGate(String id) {
        return "mi24v".equals(id) || "mi28n".equals(id) || "ka50".equals(id);
    }

    private static List<ArmorBox> readBoxes(JsonArray array, boolean armorPlate) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<ArmorBox> boxes = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = string(object, "name", armorPlate ? "armor" : "internal");
            double armorMm = armorPlate ? number(object, "armor_mm", 0.0D) : 0.0D;
            Vec center = vec(object.getAsJsonArray("center"));
            Vec half = vec(object.getAsJsonArray("half_size"));
            Vec rotation = vec(object.getAsJsonArray("rotation"));
            String frame = string(object, "frame", "hull");
            String module = string(object, "module", "");
            boolean unified = bool(object, "unified", true);
            if (half.x <= 0.0D || half.y <= 0.0D || half.z <= 0.0D) {
                continue;
            }
            boxes.add(new ArmorBox(name, armorMm, center, half, rotation, frame, module, unified));
        }
        return boxes;
    }

    private static List<ArmorBox> readEraBoxes(JsonArray array) {
        if (array == null) {
            return Collections.emptyList();
        }
        List<ArmorBox> boxes = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = string(object, "name", "kontakt1");
            String eraType = string(object, "type", "kontakt1");
            double kineticProtectionMm = number(object, "kinetic_protection_mm", 25.0D);
            double chemicalProtectionMm = number(object, "chemical_protection_mm", 400.0D);
            Vec center = vec(object.getAsJsonArray("center"));
            Vec half = vec(object.getAsJsonArray("half_size"));
            Vec rotation = vec(object.getAsJsonArray("rotation"));
            String frame = string(object, "frame", "hull");
            if (half.x <= 0.0D || half.y <= 0.0D || half.z <= 0.0D) {
                continue;
            }
            boxes.add(new ArmorBox(name, 0.0D, center, half, rotation, frame, "",
                    eraType, kineticProtectionMm, chemicalProtectionMm));
        }
        return boxes;
    }

    private static Vec vec(JsonArray array) {
        if (array == null || array.size() < 3) {
            return Vec.ZERO;
        }
        return new Vec(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static double number(JsonObject object, String key, double fallback) {
        return object != null && object.has(key) ? object.get(key).getAsDouble() : fallback;
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object != null && object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) ? object.get(key).getAsString() : fallback;
    }

    public static final class ArmorProfile {
        public final String id;
        public final double apPenetrationMm;
        public final double chemicalPenetrationMm;
        public final double fallbackIncomingPenetrationMm;
        public final double impactTolerance;
        public final double internalRayLength;
        public final boolean unboxedHitsPenetrate;
        public final boolean strictArmorGate;
        public final boolean atgmTandemWarhead;
        public final List<ArmorBox> plates;
        public final List<ArmorBox> sensitiveInternals;
        public final List<ArmorBox> engineBoxes;
        public final List<ArmorBox> ammoRacks;
        public final List<ArmorBox> trackBoxes;
        public final List<ArmorBox> moduleBoxes;
        public final List<ArmorBox> eraBoxes;
        /** Resource the volumes came from ({@code armor_mesh/<id>.geo.json}), or null for box volumes. */
        public final String meshSource;
        /** Validation warnings from the armor mesh (empty for box volumes). */
        public final List<String> meshWarnings;

        private ArmorProfile(String id, double apPenetrationMm, double chemicalPenetrationMm,
                             double fallbackIncomingPenetrationMm, double impactTolerance, double internalRayLength,
                             boolean unboxedHitsPenetrate, boolean strictArmorGate, boolean atgmTandemWarhead,
                             List<ArmorBox> plates, List<ArmorBox> sensitiveInternals,
                             List<ArmorBox> engineBoxes, List<ArmorBox> ammoRacks, List<ArmorBox> trackBoxes,
                             List<ArmorBox> moduleBoxes, List<ArmorBox> eraBoxes, String meshSource,
                             List<String> meshWarnings) {
            this.id = id;
            this.apPenetrationMm = apPenetrationMm;
            this.chemicalPenetrationMm = chemicalPenetrationMm;
            this.fallbackIncomingPenetrationMm = fallbackIncomingPenetrationMm;
            this.impactTolerance = impactTolerance;
            this.internalRayLength = internalRayLength;
            this.unboxedHitsPenetrate = unboxedHitsPenetrate;
            this.strictArmorGate = strictArmorGate;
            this.atgmTandemWarhead = atgmTandemWarhead;
            this.plates = Collections.unmodifiableList(plates);
            this.sensitiveInternals = Collections.unmodifiableList(sensitiveInternals);
            this.engineBoxes = Collections.unmodifiableList(engineBoxes);
            this.ammoRacks = Collections.unmodifiableList(ammoRacks);
            this.trackBoxes = Collections.unmodifiableList(trackBoxes);
            this.moduleBoxes = Collections.unmodifiableList(moduleBoxes);
            this.eraBoxes = Collections.unmodifiableList(eraBoxes);
            this.meshSource = meshSource;
            this.meshWarnings = List.copyOf(meshWarnings);
        }

        private static ArmorProfile empty(String id) {
            return new ArmorProfile(id, DEFAULT_AP_PENETRATION_MM, DEFAULT_CHEMICAL_PENETRATION_MM,
                    DEFAULT_FALLBACK_INCOMING_PENETRATION_MM, DEFAULT_IMPACT_TOLERANCE,
                    DEFAULT_INTERNAL_RAY_LENGTH, DEFAULT_UNBOXED_HITS_PENETRATE,
                    false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    null, List.of());
        }

        public boolean hasImpactVolumes() {
            return !this.plates.isEmpty()
                    || !this.eraBoxes.isEmpty()
                    || !this.trackBoxes.isEmpty()
                    || !this.engineBoxes.isEmpty()
                    || !this.ammoRacks.isEmpty()
                    || !this.moduleBoxes.isEmpty();
        }

        public boolean hasDebugVolumes() {
            return hasImpactVolumes() || !this.sensitiveInternals.isEmpty();
        }

        /** True when the volumes come from a Blockbench armor mesh. */
        public boolean usesArmorMesh() {
            return meshSource != null;
        }
    }

    public static final class ArmorHit {
        public enum Kind { RAY, PROXIMITY }

        public final ArmorBox plate;
        public final Vec localImpact;
        public final Vec hullImpact;
        /** Distance from the shared ray origin; proximity matches have no ray distance. */
        public final double distance;
        public final Kind kind;
        public final double proximityGap;
        /**
         * Outward normal of the entered face in the volume's frame, when the query that produced
         * this hit knows it (mesh ray hits: the true triangle normal). Null otherwise.
         */
        public final Vec normal;

        public ArmorHit(ArmorBox plate, Vec localImpact, Vec hullImpact, double distance) {
            this(plate, localImpact, hullImpact, distance, Kind.RAY, 0.0D, null);
        }

        ArmorHit(ArmorBox plate, Vec localImpact, Vec hullImpact, double distance, Vec normal) {
            this(plate, localImpact, hullImpact, distance, Kind.RAY, 0.0D, normal);
        }

        private ArmorHit(ArmorBox plate, Vec localImpact, Vec hullImpact, double distance,
                         Kind kind, double proximityGap, Vec normal) {
            this.plate = plate;
            this.localImpact = localImpact;
            this.hullImpact = hullImpact;
            this.distance = distance;
            this.kind = kind;
            this.proximityGap = proximityGap;
            this.normal = normal;
        }

        static ArmorHit proximity(ArmorBox plate, Vec localImpact, Vec hullImpact, double gap) {
            return new ArmorHit(plate, localImpact, hullImpact, Double.POSITIVE_INFINITY, Kind.PROXIMITY, gap, null);
        }

        public boolean isRayHit() {
            return kind == Kind.RAY && Double.isFinite(distance) && distance >= 0.0D;
        }

        /**
         * Outward surface normal at the contact, in the volume's frame: the entered face for a
         * mesh ray hit, otherwise the volume's normal at the contact point (boxes keep their legacy
         * face-ratio rule, meshes use the nearest triangle).
         */
        public Vec frameNormal() {
            return normal != null ? normal : plate.normalAt(localImpact);
        }
    }

    /** Closest approach of a shot segment to a volume, with a surface point in the volume's frame. */
    public record SegmentApproach(double rayDistance, double gap, Vec frameEntry) {
    }

    /**
     * One authored armor volume: its identity and armor parameters plus its geometry
     * ({@link #volume}), which is an oriented box or a triangle mesh. The class keeps its historical
     * name; every consumer goes through the geometry methods below.
     */
    public static final class ArmorBox {
        private static final String TURRET_FRAME = "turret";
        private static final String BARREL_FRAME = "barrel";

        public final String name;
        public final double armorMm;
        /** Box center; for a mesh volume the center of its bounds (use {@link #centroid()} for the solid). */
        public final Vec center;
        /** Box half extents; for a mesh volume half the size of its bounds. */
        public final Vec halfSize;
        /** Box rotation (X, then Y, then Z degrees); zero for a mesh volume. */
        public final Vec rotationDeg;
        public final String frame;
        public final String module;
        public final boolean unified;
        public final String eraType;
        public final double kineticProtectionMm;
        public final double chemicalProtectionMm;
        /** The geometry. */
        public final ArmorVolume volume;
        private final double[] bounds;

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, boolean unified) {
            this(name, armorMm, center, halfSize, rotationDeg, frame, module, unified, "", 0.0D, 0.0D,
                    new ArmorBoxVolume(center, halfSize, rotationDeg));
        }

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, String eraType, double kineticProtectionMm, double chemicalProtectionMm) {
            this(name, armorMm, center, halfSize, rotationDeg, frame, module, true,
                    eraType, kineticProtectionMm, chemicalProtectionMm,
                    new ArmorBoxVolume(center, halfSize, rotationDeg));
        }

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, boolean unified, String eraType,
                         double kineticProtectionMm, double chemicalProtectionMm, ArmorVolume volume) {
            this.name = name;
            this.armorMm = armorMm;
            this.center = center;
            this.halfSize = halfSize;
            this.rotationDeg = rotationDeg;
            this.frame = normalizeFrame(frame);
            this.module = normalizeModule(module);
            this.unified = unified;
            this.eraType = normalizeEraType(eraType);
            this.kineticProtectionMm = Math.max(0.0D, kineticProtectionMm);
            this.chemicalProtectionMm = Math.max(0.0D, chemicalProtectionMm);
            this.volume = volume;
            this.bounds = volume.bounds();
        }

        /** A mesh volume with the given identity and armor parameters. */
        static ArmorBox mesh(String name, double armorMm, ArmorMeshVolume volume, String frame, String module,
                             boolean unified, String eraType, double kineticProtectionMm,
                             double chemicalProtectionMm) {
            double[] b = volume.bounds();
            Vec center = new Vec((b[0] + b[3]) * 0.5D, (b[1] + b[4]) * 0.5D, (b[2] + b[5]) * 0.5D);
            Vec half = new Vec((b[3] - b[0]) * 0.5D, (b[4] - b[1]) * 0.5D, (b[5] - b[2]) * 0.5D);
            return new ArmorBox(name, armorMm, center, half, Vec.ZERO, frame, module, unified, eraType,
                    kineticProtectionMm, chemicalProtectionMm, volume);
        }

        public boolean isTurretFrame() {
            return TURRET_FRAME.equals(this.frame);
        }

        public boolean isBarrelFrame() {
            return BARREL_FRAME.equals(this.frame);
        }

        public boolean isMesh() {
            return volume.isMesh();
        }

        /** Solid centroid in the volume's frame. */
        public Vec centroid() {
            return volume.centroid();
        }

        private static String normalizeFrame(String frame) {
            String normalized = frame == null ? "" : frame.trim().toLowerCase(java.util.Locale.ROOT);
            return BARREL_FRAME.equals(normalized) ? BARREL_FRAME
                    : TURRET_FRAME.equals(normalized) ? TURRET_FRAME : "hull";
        }

        private static String normalizeModule(String module) {
            String normalized = module == null ? "" : module.trim().toLowerCase(java.util.Locale.ROOT);
            return normalized.replace("-", "").replace("_", "");
        }

        private static String normalizeEraType(String eraType) {
            return eraType == null ? "" : eraType.trim().toLowerCase(java.util.Locale.ROOT)
                    .replace("-", "").replace("_", "");
        }

        public Vec normalAt(Vec localImpact) {
            return volume.normalAt(localImpact);
        }

        /** Lowest point of this volume in its own frame (hull, turret or barrel coordinates). */
        public double minFrameY() {
            return volume.minY();
        }

        /**
         * Closest approach of a frame-local ray segment to this volume and a surface point the
         * shot would meet. Returns null for a degenerate segment.
         */
        SegmentApproach closestApproach(Vec frameStart, Vec frameDirection, double length) {
            return volume.closestApproach(frameStart, frameDirection, length);
        }

        public double distanceOutside(Vec localPoint) {
            return volume.distanceOutside(localPoint);
        }

        public double rayHitDistance(Vec localStart, Vec localDirection, double maxDistance, double inflation) {
            return volume.rayHitDistance(localStart, localDirection, maxDistance, inflation);
        }

        /**
         * Entry parameter of the ray into this volume's bounds grown by the skin's reach, or NaN.
         * Never larger than the exact entry, so it is a safe culling bound.
         */
        double boundsEntry(Vec start, Vec direction, double maxDistance, double inflation) {
            return ArmorMeshMath.rayAabb(bounds, 0, volume.skinPad(inflation), start.x, start.y, start.z,
                    direction.x, direction.y, direction.z, maxDistance);
        }

        /** Squared distance from a frame point to this volume's bounds (a lower bound of the exact distance). */
        double boundsDistanceSquared(Vec point) {
            return ArmorMeshMath.pointAabbDistanceSquared(bounds, 0, point.x, point.y, point.z);
        }
    }

    public static final class Vec {
        static final Vec ZERO = new Vec(0.0D, 0.0D, 0.0D);

        public final double x;
        public final double y;
        public final double z;

        Vec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vec subtract(Vec other) {
            return new Vec(this.x - other.x, this.y - other.y, this.z - other.z);
        }

        Vec add(Vec other) {
            return new Vec(this.x + other.x, this.y + other.y, this.z + other.z);
        }

        Vec scale(double scalar) {
            return new Vec(this.x * scalar, this.y * scalar, this.z * scalar);
        }

        double dot(Vec other) {
            return this.x * other.x + this.y * other.y + this.z * other.z;
        }

        double length() {
            return Math.sqrt(this.dot(this));
        }

        Vec normalize() {
            double length = length();
            if (length < 1.0E-7D) {
                return ZERO;
            }
            return new Vec(this.x / length, this.y / length, this.z / length);
        }

        Vec rotateX(double degrees) {
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return new Vec(this.x, this.y * cos - this.z * sin, this.y * sin + this.z * cos);
        }

        Vec rotateY(double degrees) {
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return new Vec(this.x * cos + this.z * sin, this.y, -this.x * sin + this.z * cos);
        }

        Vec rotateZ(double degrees) {
            double radians = Math.toRadians(degrees);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            return new Vec(this.x * cos - this.y * sin, this.x * sin + this.y * cos, this.z);
        }
    }
}
