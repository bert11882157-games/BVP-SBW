package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/** Strict client/server presentation snapshot for the v1 projectile effect extension. */
public final class BvpProjectileEffectDefinition {
    public static final ResourceLocation EXTENSION_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "projectile_effect_v1");
    /** Per-shot typed tracer extension synthesized by SBW belt policy when needed. */
    public static final ResourceLocation TRACER_EXTENSION_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "tracer_v2");
    public static final int SCHEMA = 1;
    public static final int TERMINAL_TICK = Integer.MAX_VALUE;
    /**
     * The BMP-2M AGS-30 is a BVP-owned gun-grenade projectile whose generated profile carries
     * the typed impact visual but intentionally omits the full trail/effect extension.  Keep the
     * fallback keyed to the immutable projectile profile tuple, never to a weapon/display name,
     * so ordinary SBW gun-grenade users remain on their native impact path.
     */
    private static final ResourceLocation AGS30_ROUND_ID =
            new ResourceLocation(BertsVehiclePack.MODID, "30mm_vog_30");
    private static final ResourceLocation AUTOCANNON_SHELL_MUNITION =
            new ResourceLocation(BertsVehiclePack.MODID, "autocannon_shell");
    private static final ResourceLocation AUTOCANNON_HE_IMPACT_PROFILE =
            new ResourceLocation(BertsVehiclePack.MODID, "autocannon_he");
    private static final ResourceLocation ATGM_MUNITION =
            new ResourceLocation(BertsVehiclePack.MODID, "atgm");
    private static final double ATGM_TRAIL_SIZE_MULTIPLIER = 1.0D / 3.0D;
    private static final double ATGM_ORBIT_SPEED_MULTIPLIER = 0.5D;
    private static final double ATGM_CENTRAL_TRAIL_SCALE_MULTIPLIER = 1.5D;
    private static final Map<ResolvedProjectileProfile, Optional<BvpProjectileEffectDefinition>> CACHE =
            new WeakHashMap<>();

    private final Trail trail;
    private final Impact impact;
    private final boolean guidedAtgm;

    private BvpProjectileEffectDefinition(Trail trail, Impact impact, boolean guidedAtgm) {
        this.trail = trail;
        this.impact = impact;
        this.guidedAtgm = guidedAtgm;
    }

    public Trail trail() {
        return trail;
    }

    public Impact impact() {
        return impact;
    }

    /** True when the immutable combat metadata identifies a guided ATGM effect profile. */
    public boolean isGuidedAtgm() {
        return guidedAtgm;
    }

    /**
     * Single presentation scale for guided-ATGM trail particles.  This is deliberately applied
     * by the renderer, after ordinary profile render scaling, so the one-third reduction is not
     * multiplied into both phase scale and particle width.
     */
    public double trailParticleScaleMultiplier() {
        return guidedAtgm ? ATGM_TRAIL_SIZE_MULTIPLIER : 1.0D;
    }

    /** Central trail size multiplier shared by every guided-ATGM trail consumer. */
    public double centralTrailScaleMultiplier() {
        return guidedAtgm
                ? ATGM_TRAIL_SIZE_MULTIPLIER * ATGM_CENTRAL_TRAIL_SCALE_MULTIPLIER
                : 1.0D;
    }

    /** Orbit radius follows the same single one-third presentation scale as its particles. */
    public double orbitRadiusMultiplier() {
        return guidedAtgm ? ATGM_TRAIL_SIZE_MULTIPLIER : 1.0D;
    }

    /** Orbit angular-speed multiplier shared by every guided-ATGM trail consumer. */
    public double orbitAngularSpeedMultiplier() {
        return guidedAtgm ? ATGM_ORBIT_SPEED_MULTIPLIER : 1.0D;
    }

    /** Guided central particles stay on the sampled missile axis; satellites remain offset. */
    public boolean centralTrailOnAxis() {
        return guidedAtgm;
    }

    public static BvpProjectileEffectDefinition forEntity(Entity entity) {
        return entity == null ? null : from(ProjectileProfiles.resolve(entity));
    }

    public static BvpProjectileEffectDefinition from(ResolvedProjectileProfile profile) {
        if (profile == null) {
            return null;
        }
        synchronized (CACHE) {
            Optional<BvpProjectileEffectDefinition> cached = CACHE.get(profile);
            if (cached != null) {
                return cached.orElse(null);
            }
            BvpProjectileEffectDefinition parsed = parse(profile);
            CACHE.put(profile, Optional.ofNullable(parsed));
            return parsed;
        }
    }

    /**
     * Reads the standalone synchronized tracer payload without requiring the v1 particle/trail
     * extension.  Belt policies use this path for generated profiles that intentionally carry
     * only their ordinary effect definition; the immutable profile snapshot remains authoritative.
     */
    public static EmbeddedTracer directTracer(ResolvedProjectileProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            JsonElement extension = profile.extension(TRACER_EXTENSION_ID);
            return extension != null && extension.isJsonObject()
                    ? parseTracer(extension.getAsJsonObject(), renderScale(profile))
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Returns the typed impact-only presentation for profiles that deliberately omit the v1
     * trail extension.  This is intentionally separate from {@link #from(ResolvedProjectileProfile)}
     * because trail consumers must not start treating a presentation-only fallback as a full
     * BVP projectile effect definition.
     */
    public static Impact impactOnly(ResolvedProjectileProfile profile) {
        if (!isTypedAgs30Profile(profile)) {
            return null;
        }
        return new Impact("medium");
    }

    private static boolean isTypedAgs30Profile(ResolvedProjectileProfile profile) {
        if (profile == null || profile.getId() == null
                || !BertsVehiclePack.MODID.equals(profile.getId().m_135827_())) {
            return false;
        }
        if (!AUTOCANNON_HE_IMPACT_PROFILE.equals(profile.getImpactVisualProfileId())) {
            return false;
        }
        var combat = profile.getCombat();
        if (combat == null || !AGS30_ROUND_ID.equals(combat.getRoundId())
                || !AUTOCANNON_SHELL_MUNITION.equals(combat.getMunitionType())
                || combat.getCaliberMm() == null) {
            return false;
        }
        double caliber = combat.getCaliberMm();
        return Double.isFinite(caliber) && Math.abs(caliber - 30.0D) <= 1.0E-6D;
    }

    private static BvpProjectileEffectDefinition parse(ResolvedProjectileProfile profile) {
        try {
            JsonElement extension = profile.extension(EXTENSION_ID);
            if (extension == null || !extension.isJsonObject()) {
                return null;
            }
            JsonObject root = extension.getAsJsonObject();
            requireKeys(root, "Schema", "Trail", "Impact");
            if (integer(root.get("Schema"), 1, SCHEMA) != SCHEMA) {
                return null;
            }
            boolean guidedAtgm = isAtgmProfile(profile);
            Trail trail = parseTrail(object(root, "Trail"), renderScale(profile));
            Impact impact = parseImpact(object(root, "Impact"));
            return trail == null || impact == null ? null
                    : new BvpProjectileEffectDefinition(trail, impact, guidedAtgm);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Trail parseTrail(JsonObject root, double scale) {
        if (root == null) {
            return null;
        }
        requireKeysOptional(root, Set.of("Emitter", "Phases", "SpacingBlocks", "RatePerTick",
                "LifetimeTicks", "WidthBlocks", "AttachmentOffset", "VelocityInheritance", "RandomScale"),
                "Tracer");
        JsonObject emitter = object(root, "Emitter");
        if (emitter == null) {
            return null;
        }
        ResourceLocation flameEmitter = resource(emitter.get("Flame"));
        ResourceLocation smokeEmitter = resource(emitter.get("Smoke"));
        boolean particleEmitter = hasOnly(emitter, "Flame", "Smoke")
                && flameEmitter != null && smokeEmitter != null
                && "berts_vehicle_pack:rocket_flame".equals(flameEmitter.toString())
                && "berts_vehicle_pack:rocket_smoke".equals(smokeEmitter.toString());
        boolean tracerEmitter = hasOnly(emitter, "Trail") && resource(emitter.get("Trail")) != null
                && "berts_vehicle_pack:tracer_v2".equals(resource(emitter.get("Trail")).toString());
        if (!particleEmitter && !tracerEmitter) {
            return null;
        }
        if (tracerEmitter) {
            if (!root.has("Tracer") || root.size() != 10) return null;
        } else if (root.has("Tracer")) {
            return null;
        }
        JsonArray phaseArray = array(root, "Phases");
        if (phaseArray == null || phaseArray.size() < 1 || phaseArray.size() > 3) {
            return null;
        }
        Phase[] phases = new Phase[phaseArray.size()];
        int expectedStart = 0;
        for (int i = 0; i < phaseArray.size(); i++) {
            JsonObject phase = phaseArray.get(i).isJsonObject() ? phaseArray.get(i).getAsJsonObject() : null;
            phases[i] = parsePhase(phase, expectedStart, scale);
            if (phases[i] == null) {
                return null;
            }
            if (i == 0 && !phases[i].name().equals("BOOST") && !phases[i].name().equals("SUSTAIN")) return null;
            if (i == 1 && !phases[i].name().equals("SUSTAIN") && !phases[i].name().equals("COAST")) return null;
            if (i == 2 && !phases[i].name().equals("COAST")) return null;
            expectedStart = phases[i].endTickExclusive();
        }
        if (phases[phases.length - 1].endTickExclusive() != TERMINAL_TICK) {
            return null;
        }
        Double spacing = nullablePositive(root.get("SpacingBlocks"));
        Double rate = nullablePositive(root.get("RatePerTick"));
        if ((spacing == null) == (rate == null)) {
            return null;
        }
        JsonObject life = object(root, "LifetimeTicks");
        if (life == null) {
            return null;
        }
        int flameLife;
        int smokeLife;
        int tracerLife = 0;
        if (tracerEmitter) {
            if (!hasOnly(life, "Trail")) return null;
            Integer value = integer(life.get("Trail"), 1, Integer.MAX_VALUE);
            if (value == null) return null;
            tracerLife = value;
            flameLife = smokeLife = 0;
        } else {
            if (!hasOnly(life, "Flame", "Smoke")) return null;
            Integer flame = integer(life.get("Flame"), 1, Integer.MAX_VALUE);
            Integer smoke = integer(life.get("Smoke"), 1, Integer.MAX_VALUE);
            if (flame == null || smoke == null) return null;
            flameLife = flame;
            smokeLife = smoke;
        }
        Vec3Value offset = vector(root.get("AttachmentOffset"));
        Double inheritance = range(root.get("VelocityInheritance"), 0.0D, 1.0D);
        JsonArray random = array(root, "RandomScale");
        if (offset == null || inheritance == null || random == null || random.size() != 2) return null;
        Double randomMin = finite(random.get(0));
        Double randomMax = finite(random.get(1));
        if (randomMin == null || randomMax == null || randomMin <= 0.0D || randomMax < randomMin) return null;
        Double width = positive(root.get("WidthBlocks"));
        if (width == null) return null;
        EmbeddedTracer tracer = tracerEmitter ? parseTracer(object(root, "Tracer"), scale) : null;
        if (tracerEmitter && tracer == null) return null;
        return new Trail(particleEmitter, particleEmitter,
                phases, spacing, rate, flameLife, smokeLife, tracerLife,
                width * scale, offset, inheritance, randomMin, randomMax, tracer);
    }

    private static Phase parsePhase(JsonObject root, int expectedStart, double scale) {
        if (root == null) return null;
        requireKeys(root, "Name", "StartTick", "EndTickExclusive", "Flame", "Smoke", "Scale", "Orbit");
        String name = string(root.get("Name"));
        Integer start = integer(root.get("StartTick"), 0, TERMINAL_TICK - 1);
        Integer end = integer(root.get("EndTickExclusive"), 1, TERMINAL_TICK);
        Boolean flame = booleanValue(root, "Flame");
        Boolean smoke = booleanValue(root, "Smoke");
        Double phaseScale = positive(root.get("Scale"));
        if (name == null || start == null || end == null || start != expectedStart || end <= start
                || flame == null || smoke == null || phaseScale == null) return null;
        if (!(name.equals("BOOST") || name.equals("SUSTAIN") || name.equals("COAST"))) return null;
        Orbit orbit = parseOrbit(root.get("Orbit"), scale);
        if (orbit == INVALID_ORBIT) return null;
        return new Phase(name, start, end, flame, smoke, phaseScale * scale, orbit);
    }

    private static Orbit parseOrbit(JsonElement value, double scale) {
        if (value == null || value.isJsonNull()) return null;
        JsonObject root = value.isJsonObject() ? value.getAsJsonObject() : null;
        if (root == null) return INVALID_ORBIT;
        requireKeys(root, "Count", "RadiusBlocks", "AngularSpeedRadiansPerTick", "Scale");
        Integer count = integer(root.get("Count"), 1, 64);
        Double radius = positive(root.get("RadiusBlocks"));
        Double speed = finite(root.get("AngularSpeedRadiansPerTick"));
        Double orbitScale = positive(root.get("Scale"));
        if (count == null || radius == null || speed == null || orbitScale == null) return INVALID_ORBIT;
        return new Orbit(count, radius * scale, speed, orbitScale * scale);
    }

    private static EmbeddedTracer parseTracer(JsonObject root, double scale) {
        if (root == null) return null;
        requireKeys(root, "Enabled", "ColorRgb", "EveryNthShot", "LengthBlocks", "WidthBlocks",
                "Opacity", "LifetimeTicks", "Core", "Glow");
        Boolean enabled = booleanValue(root, "Enabled");
        JsonArray rgb = array(root, "ColorRgb");
        Integer every = integer(root.get("EveryNthShot"), 1, Integer.MAX_VALUE);
        Double length = positive(root.get("LengthBlocks"));
        Double width = positive(root.get("WidthBlocks"));
        Double opacity = range(root.get("Opacity"), 0.0D, 1.0D);
        Integer lifetime = integer(root.get("LifetimeTicks"), 1, Integer.MAX_VALUE);
        JsonObject core = object(root, "Core");
        JsonObject glow = object(root, "Glow");
        Double cr = rgb == null || rgb.size() != 3 ? null : range(rgb.get(0), 0.0D, 255.0D);
        Double cg = rgb == null || rgb.size() != 3 ? null : range(rgb.get(1), 0.0D, 255.0D);
        Double cb = rgb == null || rgb.size() != 3 ? null : range(rgb.get(2), 0.0D, 255.0D);
        Double cwidth = core == null ? null : positive(core.get("WidthScale"));
        Double copacity = core == null ? null : range(core.get("OpacityScale"), 0.0D, 1.0D);
        Double gwidth = glow == null ? null : positive(glow.get("WidthScale"));
        Double gopacity = glow == null ? null : range(glow.get("OpacityScale"), 0.0D, 1.0D);
        if (enabled == null || !enabled || cr == null || cg == null || cb == null || every == null || length == null
                || width == null || opacity == null || lifetime == null || cwidth == null || copacity == null
                || gwidth == null || gopacity == null) return null;
        return new EmbeddedTracer((float) (cr / 255.0D), (float) (cg / 255.0D), (float) (cb / 255.0D), every,
                length * scale, width * scale, opacity.floatValue(), lifetime,
                cwidth * scale, copacity.floatValue(), gwidth * scale, gopacity.floatValue());
    }

    private static Impact parseImpact(JsonObject root) {
        if (root == null) return null;
        requireKeys(root, "Explosion", "WaterExplosion");
        String explosion = string(root.get("Explosion"));
        String water = string(root.get("WaterExplosion"));
        return explosion != null && (explosion.equals("none") || explosion.equals("medium") || explosion.equals("large"))
                && "same".equals(water) ? new Impact(explosion) : null;
    }

    private static double renderScale(ResolvedProjectileProfile profile) {
        double scale = profile.getRenderScale();
        return Double.isFinite(scale) && scale > 0.0D ? Math.min(scale, 64.0D) : 1.0D;
    }

    private static boolean isAtgmProfile(ResolvedProjectileProfile profile) {
        var combat = profile == null ? null : profile.getCombat();
        return combat != null && ATGM_MUNITION.equals(combat.getMunitionType());
    }

    private static void requireKeys(JsonObject object, String... keys) {
        Set<String> expected = Set.of(keys);
        for (String key : object.keySet()) if (!expected.contains(key)) throw new IllegalArgumentException("unknown key");
        for (String key : expected) if (!object.has(key)) throw new IllegalArgumentException("missing key");
    }

    private static void requireKeysOptional(JsonObject object, Set<String> required, String optional) {
        for (String key : object.keySet()) {
            if (!required.contains(key) && !optional.equals(key)) throw new IllegalArgumentException("unknown key");
        }
        for (String key : required) if (!object.has(key)) throw new IllegalArgumentException("missing key");
    }

    private static boolean hasOnly(JsonObject object, String... keys) {
        Set<String> expected = Set.of(keys);
        for (String key : object.keySet()) if (!expected.contains(key)) return false;
        return object.size() == expected.size();
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static ResourceLocation resource(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        try { return new ResourceLocation(value.getAsString()); } catch (RuntimeException ignored) { return null; }
    }

    private static String string(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static Boolean booleanValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean() : null;
    }

    private static Integer integer(JsonElement value, int min, int max) {
        Double parsed = finite(value);
        if (parsed == null || parsed != Math.rint(parsed) || parsed < min || parsed > max) return null;
        return parsed.intValue();
    }

    private static Double positive(JsonElement value) {
        Double parsed = finite(value);
        return parsed != null && parsed > 0.0D ? parsed : null;
    }

    private static Double nullablePositive(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        return positive(value);
    }

    private static Double range(JsonElement value, double min, double max) {
        Double parsed = finite(value);
        return parsed != null && parsed >= min && parsed <= max ? parsed : null;
    }

    private static Double finite(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try { double parsed = value.getAsDouble(); return Double.isFinite(parsed) ? parsed : null; }
        catch (RuntimeException ignored) { return null; }
    }

    private static Vec3Value vector(JsonElement value) {
        JsonArray array = value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
        if (array == null || array.size() != 3) return null;
        Double x = finite(array.get(0)), y = finite(array.get(1)), z = finite(array.get(2));
        return x == null || y == null || z == null ? null : new Vec3Value(x, y, z);
    }

    public record Trail(boolean flame, boolean smoke, Phase[] phases, Double spacingBlocks, Double ratePerTick,
                        int flameLifetimeTicks, int smokeLifetimeTicks, int tracerLifetimeTicks,
                        double widthBlocks, Vec3Value attachmentOffset, double velocityInheritance,
                        double randomScaleMin, double randomScaleMax, EmbeddedTracer tracer) {
        public Trail {
            phases = phases == null ? new Phase[0] : phases.clone();
        }

        @Override
        public Phase[] phases() {
            return phases.clone();
        }

        public Phase phaseAt(int age) {
            long tick = Math.max(0L, age);
            for (Phase phase : phases) if (tick >= phase.startTick() && tick < phase.endTickExclusive()) return phase;
            return phases[phases.length - 1];
        }
        public boolean isTracer() { return tracer != null; }
        public boolean isParticle() { return tracer == null; }
        public int sampleCount(double distance) {
            double value = spacingBlocks != null ? Math.ceil(Math.max(0.0D, distance) / spacingBlocks)
                    : Math.ceil(Math.max(0.0D, ratePerTick));
            return (int) Math.max(1L, Math.min(8L, (long) value));
        }
    }

    public record Phase(String name, int startTick, int endTickExclusive, boolean flame, boolean smoke,
                        double scale, Orbit orbit) { }
    public record Orbit(int count, double radiusBlocks, double angularSpeedRadiansPerTick, double scale) { }
    public record EmbeddedTracer(float red, float green, float blue, int everyNthShot,
                                 double lengthBlocks, double widthBlocks, float opacity, int lifetimeTicks,
                                 double coreWidthScale, float coreOpacityScale,
                                 double glowWidthScale, float glowOpacityScale) { }
    public record Impact(String explosion) { }
    public record Vec3Value(double x, double y, double z) { }

    private static final Orbit INVALID_ORBIT = new Orbit(0, Double.NaN, Double.NaN, Double.NaN);
}
