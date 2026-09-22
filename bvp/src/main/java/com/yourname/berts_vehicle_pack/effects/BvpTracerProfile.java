package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.data.projectile.ProjectileTrailMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** Immutable client presentation parsed from the synchronized projectile profile snapshot. */
public record BvpTracerProfile(
        float red,
        float green,
        float blue,
        int everyNthShot,
        double lengthBlocks,
        double widthBlocks,
        float opacity,
        int lifetimeTicks,
        double coreWidthScale,
        float coreOpacityScale,
        double glowWidthScale,
        float glowOpacityScale) {
    private static final ResourceLocation TANK_SHELL_APFSDS =
            new ResourceLocation("berts_vehicle_pack", "tank_shell_apfsds");
    private static final ResourceLocation TANK_SHELL_HEATFS =
            new ResourceLocation("berts_vehicle_pack", "tank_shell_heatfs");
    private static final ResourceLocation TANK_SHELL_HE =
            new ResourceLocation("berts_vehicle_pack", "tank_shell_he");

    /**
     * Resolves typed policy before legacy v1 effects. SUPPRESS is terminal, GREEN can be a
     * standalone synchronized tracer payload, and typed tank-shell profiles receive a white
     * tracer even when their generated v1 effect intentionally contains only particles.
     */
    public static BvpTracerProfile forEntity(Entity entity) {
        if (entity == null) return null;
        ResolvedProjectileProfile profile = ProjectileProfiles.resolve(entity);
        if (profile == null || profile.getTrailMode() == ProjectileTrailMode.SUPPRESS) return null;

        BvpProjectileEffectDefinition.EmbeddedTracer direct =
                BvpProjectileEffectDefinition.directTracer(profile);
        if (isTankShell(profile.getVisualProfileId())) {
            return direct == null ? defaultWhite(profile) : white(direct);
        }
        if (direct != null) {
            return from(direct);
        }

        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.from(profile);
        if (definition == null || !definition.trail().isTracer()) return null;
        BvpProjectileEffectDefinition.EmbeddedTracer tracer = definition.trail().tracer();
        return from(tracer);
    }

    public static boolean enabled(Entity entity) {
        return forEntity(entity) != null;
    }

    public boolean shouldRender(Entity entity) {
        if (everyNthShot <= 1) {
            return true;
        }
        long sequence = entity == null ? 0L : ProjectileProfiles.shotSequence(entity);
        return sequence > 0L && sequence % everyNthShot == 0L;
    }

    private static BvpTracerProfile from(BvpProjectileEffectDefinition.EmbeddedTracer tracer) {
        return new BvpTracerProfile(tracer.red(), tracer.green(), tracer.blue(), tracer.everyNthShot(),
                tracer.lengthBlocks(), tracer.widthBlocks(), tracer.opacity(), tracer.lifetimeTicks(),
                tracer.coreWidthScale(), tracer.coreOpacityScale(),
                tracer.glowWidthScale(), tracer.glowOpacityScale());
    }

    private static BvpTracerProfile white(BvpProjectileEffectDefinition.EmbeddedTracer tracer) {
        return new BvpTracerProfile(1.0F, 1.0F, 1.0F, tracer.everyNthShot(),
                tracer.lengthBlocks(), tracer.widthBlocks(), tracer.opacity(), tracer.lifetimeTicks(),
                tracer.coreWidthScale(), tracer.coreOpacityScale(),
                tracer.glowWidthScale(), tracer.glowOpacityScale());
    }

    private static BvpTracerProfile defaultWhite(ResolvedProjectileProfile profile) {
        double scale = profile.getRenderScale();
        if (!Double.isFinite(scale) || scale <= 0.0D) scale = 1.0D;
        scale = Math.min(scale, 64.0D);
        return new BvpTracerProfile(1.0F, 1.0F, 1.0F, 1,
                4.0D * scale, 0.025D * scale, 0.9F, 3,
                1.0D * scale, 1.0F, 4.0D * scale, 0.25F);
    }

    private static boolean isTankShell(ResourceLocation id) {
        return TANK_SHELL_APFSDS.equals(id) || TANK_SHELL_HEATFS.equals(id) || TANK_SHELL_HE.equals(id);
    }

}
