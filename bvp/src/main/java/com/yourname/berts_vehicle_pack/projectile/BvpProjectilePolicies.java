package com.yourname.berts_vehicle_pack.projectile;

import com.atsuishio.superbwarfare.api.projectile.*;
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltTracer;
import com.atsuishio.superbwarfare.data.gun.ProjectileBeltFamily;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponCadencePolicies;
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponCadenceInput;
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** Pack-owned projectile identities and optional tracer presentation; no live entity access. */
public final class BvpProjectilePolicies implements ProjectileProfilePolicy {
    private static final BvpProjectilePolicies INSTANCE = new BvpProjectilePolicies();
    private static final String TRACER = "berts_vehicle_pack:tracer_v2";
    private static final String EFFECT = "berts_vehicle_pack:projectile_effect_v1";

    public static void register() {
        ProjectileProfilePolicies.register(new ResourceLocation(BertsVehiclePack.MODID, "projectile_policy"),
                INSTANCE);
        VehicleWeaponCadencePolicies.register(new ResourceLocation(BertsVehiclePack.MODID, "cyclic_cadence"),
                BvpProjectilePolicies::eventRpm);
    }

    public static Integer eventRpm(VehicleWeaponCadenceInput input) {
        return input.getFamily() == ProjectileBeltFamily.KPVT
                || INSTANCE.roundMatches(ProjectileRoundQuery.CYCLIC_145, input.getRoundId())
                ? 600 : null;
    }

    @Override
    public ProjectilePresentationPatch presentation(ProjectilePresentationInput input) {
        ResolvedProjectileProfile template = input.getTemplate();
        JsonObject extensions = template.extensions();
        if (input.getPurpose() != ProjectilePresentationPurpose.SHOT_TRACER
                && !isPackId(template.getId()) && !extensions.has(TRACER) && !extensions.has(EFFECT)) return null;
        JsonObject tracer = object(extensions.get(TRACER));
        ProjectileTrailMode trailMode = template.getTrailMode();
        float scale = template.getRenderScale();
        switch (input.getPurpose()) {
            case SHOT_TRACER -> {
                ProjectileBeltTracer policy = input.getTracer();
                if (policy == null || policy == ProjectileBeltTracer.INHERIT) return null;
                if (policy == ProjectileBeltTracer.NONE || policy == ProjectileBeltTracer.SUPPRESS) {
                    if (tracer != null) tracer.addProperty("Enabled", false);
                    trailMode = ProjectileTrailMode.SUPPRESS;
                } else {
                    if (tracer == null) {
                        tracer = defaultTracer();
                        extensions.add(TRACER, tracer);
                    }
                    tracer.addProperty("Enabled", true);
                    tracer.add("ColorRgb", tracerColor(policy));
                    trailMode = ProjectileTrailMode.REPLACE;
                }
            }
            case SMALL_WHITE_TRACER -> {
                if (template.getCombat() == null || !roundMatches(ProjectileRoundQuery.SMALL_WHITE_TRACER,
                        template.getCombat().getRoundId())) return null;
                if (tracer == null) {
                    tracer = defaultTracer();
                    extensions.add(TRACER, tracer);
                }
                tracer.addProperty("Enabled", true);
                tracer.add("ColorRgb", color(255, 255, 255));
                trailMode = ProjectileTrailMode.REPLACE;
            }
            case IMPACT_FRAGMENT -> {
                if (tracer == null) {
                    JsonObject effect = object(extensions.get(EFFECT));
                    JsonObject trail = effect == null ? null : object(effect.get("Trail"));
                    JsonObject nested = trail == null ? null : object(trail.get("Tracer"));
                    if (nested == null) return null;
                    tracer = nested.deepCopy();
                    extensions.add(TRACER, tracer);
                }
                tracer.add("ColorRgb", color(255, 255, 255));
                tracer.addProperty("Opacity", 1.0);
                scale = input.getRenderScale();
            }
        }
        return new ProjectilePresentationPatch(trailMode, scale, extensions);
    }

    @Override
    public boolean roundMatches(ProjectileRoundQuery query, ResourceLocation roundId) {
        if (!isPackId(roundId)) return false;
        return switch (query) {
            case CYCLIC_145 -> roundId.m_135815_().toLowerCase(Locale.ROOT).startsWith("kpvt_");
            case SMALL_WHITE_TRACER -> roundId.m_135815_().equals("pg9") || roundId.m_135815_().equals("og9");
        };
    }

    @Override
    public ProjectileTerrainDecision terrain(ProjectileTerrainInput input) {
        return input.getVehicleMountedOwner() && (isPackId(input.getProfileId()) || isPackId(input.getWeaponId()))
                ? ProjectileTerrainDecision.KEEP : ProjectileTerrainDecision.INHERIT;
    }

    private static boolean isPackId(ResourceLocation id) {
        return id != null && id.m_135827_().equals(BertsVehiclePack.MODID);
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }


    /**
     * Tracer RGB per belt policy: War Thunder's tracer glow colours (gameparams tracerColors, stored BGRA). RED and
     * GREEN keep the pack's established tones.
     */
    static JsonArray tracerColor(ProjectileBeltTracer policy) {
        return switch (policy) {
            case GREEN -> color(72, 255, 96);
            case WHITE -> color(213, 237, 255);
            case LIGHT_RED -> color(255, 144, 132);
            case BRIGHT_RED -> color(255, 60, 20);
            case DARK_RED -> color(210, 0, 0);
            case PINK -> color(255, 65, 154);
            default -> color(255, 32, 32);
        };
    }

    private static JsonArray color(int red, int green, int blue) {
        JsonArray color = new JsonArray();
        color.add(red);
        color.add(green);
        color.add(blue);
        return color;
    }

    private static JsonObject defaultTracer() {
        JsonObject tracer = new JsonObject();
        tracer.addProperty("Enabled", true);
        tracer.add("ColorRgb", color(255, 255, 255));
        tracer.addProperty("EveryNthShot", 1);
        tracer.addProperty("LengthBlocks", 4.0);
        tracer.addProperty("WidthBlocks", 0.025);
        tracer.addProperty("Opacity", 0.9);
        tracer.addProperty("LifetimeTicks", 3);
        JsonObject core = new JsonObject();
        core.addProperty("WidthScale", 1.0);
        core.addProperty("OpacityScale", 1.0);
        tracer.add("Core", core);
        JsonObject glow = new JsonObject();
        glow.addProperty("WidthScale", 4.0);
        glow.addProperty("OpacityScale", 0.25);
        tracer.add("Glow", glow);
        return tracer;
    }
}
