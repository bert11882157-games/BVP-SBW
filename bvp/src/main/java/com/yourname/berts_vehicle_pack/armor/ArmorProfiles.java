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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ArmorProfiles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Map<String, ArmorProfile> CACHE = new HashMap<>();
    private static final double DEFAULT_AP_PENETRATION_MM = 500.0D;
    private static final double DEFAULT_CHEMICAL_PENETRATION_MM = 700.0D;
    private static final double DEFAULT_FALLBACK_INCOMING_PENETRATION_MM = 500.0D;
    private static final double DEFAULT_IMPACT_TOLERANCE = 0.25D;
    private static final double DEFAULT_INTERNAL_RAY_LENGTH = 10.0D;
    private static final boolean DEFAULT_UNBOXED_HITS_PENETRATE = true;

    private ArmorProfiles() {
    }

    public static ArmorProfile get(String id) {
        synchronized (CACHE) {
            ArmorProfile cached = CACHE.get(id);
            if (cached != null) {
                return cached;
            }
            ArmorProfile loaded = load(id);
            CACHE.put(id, loaded);
            return loaded;
        }
    }

    private static ArmorProfile load(String id) {
        String resource = "/data/" + BertsVehiclePack.MODID + "/armor/" + id + ".json";
        try (InputStream stream = ArmorProfiles.class.getResourceAsStream(resource)) {
            if (stream == null) {
                LOGGER.warn("[BVP Armor] Missing armor profile '{}'; using empty fallback.", id);
                return ArmorProfile.empty(id);
            }
            JsonObject root = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
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
                    plates, internals, engines, ammoRacks, tracks, modules, eraBoxes);
        } catch (Exception exception) {
            LOGGER.warn("[BVP Armor] Failed to load armor profile '{}'; using empty fallback.", id, exception);
            return ArmorProfile.empty(id);
        }
    }

    private static boolean isEngineBoxName(String name) {
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

        private ArmorProfile(String id, double apPenetrationMm, double chemicalPenetrationMm,
                             double fallbackIncomingPenetrationMm, double impactTolerance, double internalRayLength,
                             boolean unboxedHitsPenetrate, boolean strictArmorGate, boolean atgmTandemWarhead,
                             List<ArmorBox> plates, List<ArmorBox> sensitiveInternals,
                             List<ArmorBox> engineBoxes, List<ArmorBox> ammoRacks, List<ArmorBox> trackBoxes,
                             List<ArmorBox> moduleBoxes, List<ArmorBox> eraBoxes) {
            this.id = id;
            this.apPenetrationMm = apPenetrationMm;
            this.chemicalPenetrationMm = chemicalPenetrationMm;
            this.fallbackIncomingPenetrationMm = fallbackIncomingPenetrationMm;
            this.impactTolerance = impactTolerance;
            this.internalRayLength = internalRayLength;
            this.unboxedHitsPenetrate = unboxedHitsPenetrate;
            this.strictArmorGate = strictArmorGate;
            this.atgmTandemWarhead = atgmTandemWarhead;
            this.plates = plates;
            this.sensitiveInternals = sensitiveInternals;
            this.engineBoxes = engineBoxes;
            this.ammoRacks = ammoRacks;
            this.trackBoxes = trackBoxes;
            this.moduleBoxes = moduleBoxes;
            this.eraBoxes = eraBoxes;
        }

        private static ArmorProfile empty(String id) {
            return new ArmorProfile(id, DEFAULT_AP_PENETRATION_MM, DEFAULT_CHEMICAL_PENETRATION_MM,
                    DEFAULT_FALLBACK_INCOMING_PENETRATION_MM, DEFAULT_IMPACT_TOLERANCE,
                    DEFAULT_INTERNAL_RAY_LENGTH, DEFAULT_UNBOXED_HITS_PENETRATE,
                    false, false, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
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

        public ArmorHit(ArmorBox plate, Vec localImpact, Vec hullImpact, double distance) {
            this(plate, localImpact, hullImpact, distance, Kind.RAY, 0.0D);
        }

        private ArmorHit(ArmorBox plate, Vec localImpact, Vec hullImpact, double distance,
                         Kind kind, double proximityGap) {
            this.plate = plate;
            this.localImpact = localImpact;
            this.hullImpact = hullImpact;
            this.distance = distance;
            this.kind = kind;
            this.proximityGap = proximityGap;
        }

        static ArmorHit proximity(ArmorBox plate, Vec localImpact, Vec hullImpact, double gap) {
            return new ArmorHit(plate, localImpact, hullImpact, Double.POSITIVE_INFINITY, Kind.PROXIMITY, gap);
        }

        public boolean isRayHit() {
            return kind == Kind.RAY && Double.isFinite(distance) && distance >= 0.0D;
        }
    }

    /** Closest approach of a shot segment to a box, with the entered face point in the box's frame. */
    record SegmentApproach(double rayDistance, double gap, Vec frameEntry) {
    }

    public static final class ArmorBox {
        private static final String TURRET_FRAME = "turret";
        private static final String BARREL_FRAME = "barrel";
        private static final double RAY_AXIS_EPSILON = 1.0E-7D;

        public final String name;
        public final double armorMm;
        public final Vec center;
        public final Vec halfSize;
        public final Vec rotationDeg;
        public final String frame;
        public final String module;
        public final boolean unified;
        public final String eraType;
        public final double kineticProtectionMm;
        public final double chemicalProtectionMm;

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, boolean unified) {
            this(name, armorMm, center, halfSize, rotationDeg, frame, module, unified, "", 0.0D, 0.0D);
        }

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, String eraType, double kineticProtectionMm, double chemicalProtectionMm) {
            this(name, armorMm, center, halfSize, rotationDeg, frame, module, true,
                    eraType, kineticProtectionMm, chemicalProtectionMm);
        }

        private ArmorBox(String name, double armorMm, Vec center, Vec halfSize, Vec rotationDeg, String frame,
                         String module, boolean unified, String eraType,
                         double kineticProtectionMm, double chemicalProtectionMm) {
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
        }

        public boolean isTurretFrame() {
            return TURRET_FRAME.equals(this.frame);
        }

        public boolean isBarrelFrame() {
            return BARREL_FRAME.equals(this.frame);
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
            Vec boxPoint = toBoxSpace(localImpact);
            double nx = Math.abs(boxPoint.x / this.halfSize.x);
            double ny = Math.abs(boxPoint.y / this.halfSize.y);
            double nz = Math.abs(boxPoint.z / this.halfSize.z);
            Vec normal;
            if (nx >= ny && nx >= nz) {
                normal = new Vec(Math.signum(boxPoint.x), 0.0D, 0.0D);
            } else if (ny >= nz) {
                normal = new Vec(0.0D, Math.signum(boxPoint.y), 0.0D);
            } else {
                normal = new Vec(0.0D, 0.0D, Math.signum(boxPoint.z));
            }
            return fromBoxDirection(normal).normalize();
        }

        /** Lowest point of this box in its own frame (hull, turret or barrel coordinates). */
        public double minFrameY() {
            Vec axisX = rotate(new Vec(this.halfSize.x, 0.0D, 0.0D));
            Vec axisY = rotate(new Vec(0.0D, this.halfSize.y, 0.0D));
            Vec axisZ = rotate(new Vec(0.0D, 0.0D, this.halfSize.z));
            return this.center.y - (Math.abs(axisX.y) + Math.abs(axisY.y) + Math.abs(axisZ.y));
        }

        /**
         * Closest approach of a frame-local ray segment to this box and the face the shot would
         * enter if displaced onto it. Returns null for a degenerate segment.
         */
        SegmentApproach closestApproach(Vec frameStart, Vec frameDirection, double length) {
            Vec start = toBoxSpace(frameStart);
            Vec direction = toBoxDirection(frameDirection).normalize();
            if (direction.length() < 1.0E-6D) {
                return null;
            }
            RayBoxProximity.Approach approach = RayBoxProximity.approach(
                    new double[] {start.x, start.y, start.z},
                    new double[] {direction.x, direction.y, direction.z},
                    length,
                    new double[] {this.halfSize.x, this.halfSize.y, this.halfSize.z});
            if (approach == null) {
                return null;
            }
            double[] entry = approach.entry();
            Vec frameEntry = rotate(new Vec(entry[0], entry[1], entry[2])).add(this.center);
            return new SegmentApproach(approach.rayDistance(), approach.gap(), frameEntry);
        }

        public double distanceOutside(Vec localPoint) {
            Vec boxPoint = toBoxSpace(localPoint);
            double dx = Math.max(0.0D, Math.abs(boxPoint.x) - this.halfSize.x);
            double dy = Math.max(0.0D, Math.abs(boxPoint.y) - this.halfSize.y);
            double dz = Math.max(0.0D, Math.abs(boxPoint.z) - this.halfSize.z);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double rayHitDistance(Vec localStart, Vec localDirection, double maxDistance, double inflation) {
            Vec start = toBoxSpace(localStart);
            Vec direction = toBoxDirection(localDirection).normalize();
            double tMin = 0.0D;
            double tMax = maxDistance;

            double half = this.halfSize.x + inflation;
            if (Math.abs(direction.x) < RAY_AXIS_EPSILON) {
                if (start.x < -half || start.x > half) {
                    return Double.NaN;
                }
            } else {
                double a = (-half - start.x) / direction.x;
                double b = (half - start.x) / direction.x;
                double near = Math.min(a, b);
                double far = Math.max(a, b);
                tMin = Math.max(tMin, near);
                tMax = Math.min(tMax, far);
                if (tMin > tMax) {
                    return Double.NaN;
                }
            }

            half = this.halfSize.y + inflation;
            if (Math.abs(direction.y) < RAY_AXIS_EPSILON) {
                if (start.y < -half || start.y > half) {
                    return Double.NaN;
                }
            } else {
                double a = (-half - start.y) / direction.y;
                double b = (half - start.y) / direction.y;
                double near = Math.min(a, b);
                double far = Math.max(a, b);
                tMin = Math.max(tMin, near);
                tMax = Math.min(tMax, far);
                if (tMin > tMax) {
                    return Double.NaN;
                }
            }

            half = this.halfSize.z + inflation;
            if (Math.abs(direction.z) < RAY_AXIS_EPSILON) {
                if (start.z < -half || start.z > half) {
                    return Double.NaN;
                }
            } else {
                double a = (-half - start.z) / direction.z;
                double b = (half - start.z) / direction.z;
                double near = Math.min(a, b);
                double far = Math.max(a, b);
                tMin = Math.max(tMin, near);
                tMax = Math.min(tMax, far);
                if (tMin > tMax) {
                    return Double.NaN;
                }
            }

            if (tMax < 0.0D || tMin > maxDistance) {
                return Double.NaN;
            }
            return Math.max(0.0D, tMin);
        }

        private Vec toBoxSpace(Vec localPoint) {
            return inverseRotate(localPoint.subtract(this.center));
        }

        private Vec toBoxDirection(Vec localDirection) {
            return inverseRotate(localDirection);
        }

        private Vec fromBoxDirection(Vec boxDirection) {
            return rotate(boxDirection);
        }

        private Vec inverseRotate(Vec value) {
            Vec result = value.rotateZ(-this.rotationDeg.z);
            result = result.rotateY(-this.rotationDeg.y);
            return result.rotateX(-this.rotationDeg.x);
        }

        private Vec rotate(Vec value) {
            Vec result = value.rotateX(this.rotationDeg.x);
            result = result.rotateY(this.rotationDeg.y);
            return result.rotateZ(this.rotationDeg.z);
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
