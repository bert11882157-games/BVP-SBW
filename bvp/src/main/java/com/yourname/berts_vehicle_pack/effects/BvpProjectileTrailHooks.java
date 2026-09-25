package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.tools.ExplosionFxContext;
import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.yourname.berts_vehicle_pack.init.ModSounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

public final class BvpProjectileTrailHooks {
    public static final ResourceLocation AMMO_RACK_EXPLOSION_PROFILE_ID =
            new ResourceLocation("berts_vehicle_pack", "ammo_rack_explosion");
    private static final float AMMO_RACK_EXPLOSION_VOLUME = 12.0F;
    private static final float AMMO_RACK_EXPLOSION_PITCH = 0.72F;
    private static final ResourceLocation EXPLOSION_FX_HANDLER_ID =
            new ResourceLocation("berts_vehicle_pack", "projectile_impact");
    private static final int MAX_PENDING_REPLACEMENTS = 256;
    private static final double SAME_IMPACT_DISTANCE_SQR = 0.0625D;
    private static final Map<Entity, PendingReplacement> PENDING_REPLACEMENTS = new WeakHashMap<>();
    /** Fireball radius (m) from which a TNT blast keeps SBW's native explosion recipe. */
    private static final double TNT_NATIVE_RECIPE_RADIUS = 1.0D;

    private BvpProjectileTrailHooks() {
    }

    public static void registerExplosionFxHandler() {
        com.atsuishio.superbwarfare.api.effect.MissilePresentation.setNozzleResolver(entity -> {
            var profile = ProjectileProfiles.resolve(entity);
            var definition = BvpProjectileEffectDefinition.from(profile);
            if (definition == null || !definition.isGuidedAtgm()) return null;
            var shape = BvpMissileExhaustGeometry.forProfile(profile);
            return shape == null ? null : new com.atsuishio.superbwarfare.api.effect.MissilePresentation.NozzleOffset(
                    entity.m_20206_() * .5, shape.rear());
        });
        ParticleTool.registerExplosionFxHandler(EXPLOSION_FX_HANDLER_ID,
                BvpProjectileTrailHooks::replaceSbwExplosionFx);
    }

    /**
     * Records that the post-resolution provider already emitted the explosion for this impact.
     * The native CustomExplosion still owns gameplay; this marker only suppresses its duplicate
     * presentation during the same server tick and at the same impact point.
     */
    public static void deferExplosionPresentation(Entity source, Level level, net.minecraft.world.phys.Vec3 position) {
        if (source == null || level == null || !finite(position)) {
            return;
        }
        synchronized (PENDING_REPLACEMENTS) {
            trimPendingReplacements();
            PENDING_REPLACEMENTS.put(source,
                    new PendingReplacement(level, level.m_46467_(), position));
        }
    }

    private static boolean replaceSbwExplosionFx(Level level, ExplosionFxContext context) {
        if (AMMO_RACK_EXPLOSION_PROFILE_ID.equals(context.getProfileId())) {
            if (com.atsuishio.superbwarfare.api.effect.MissilePresentation.effect(
                    level, context.getParticlePosition(), 6F, true)) return true;
            BvpLeanImpactEffects.spawnAmmoRackExplosion(level, context.getParticlePosition());
            level.m_6263_(null,
                    context.getParticlePosition().f_82479_,
                    context.getParticlePosition().f_82480_,
                    context.getParticlePosition().f_82481_,
                    ModSounds.EXPLOSION_MEDIUM.get(), SoundSource.BLOCKS,
                    AMMO_RACK_EXPLOSION_VOLUME, AMMO_RACK_EXPLOSION_PITCH);
            return true;
        }
        Entity directSource = context.getDirectSource();
        // Charges with a fireball of a metre or more (>= 8 kg TNT) keep SBW's native recipe (sound layers, dust,
        // debris) under the SBW fireball, even after an armor hit already showed its local impact effects.
        if (com.atsuishio.superbwarfare.tools.blast.TntBlast.fireballRadius(directSource) >= TNT_NATIVE_RECIPE_RADIUS) {
            consumeDeferredExplosionPresentation(level, directSource, context.getParticlePosition());
            return false;
        }
        if (consumeDeferredExplosionPresentation(level, directSource, context.getParticlePosition())) {
            return true;
        }
        var profile = directSource == null ? null : ProjectileProfiles.resolve(directSource);
        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.from(profile);
        // Gun grenades carry an impact-only profile, without the missile/tracer trail extension.
        // The native explosion callback must honor it just like the armor-impact callback does.
        BvpProjectileEffectDefinition.Impact impact = definition != null
                ? definition.impact() : BvpProjectileEffectDefinition.impactOnly(profile);
        if (impact == null || "none".equals(impact.explosion())) {
            return false;
        }
        BvpLeanImpactEffects.spawn(level, context.getParticlePosition(), directSource);
        return true;
    }

    private static boolean consumeDeferredExplosionPresentation(Level level, Entity source,
                                                                net.minecraft.world.phys.Vec3 position) {
        if (source == null || level == null || !finite(position)) {
            return false;
        }
        synchronized (PENDING_REPLACEMENTS) {
            PendingReplacement pending = PENDING_REPLACEMENTS.get(source);
            if (pending == null) {
                return false;
            }
            if (pending.level != level || pending.gameTime != level.m_46467_()
                    || pending.position.m_82554_(position) > SAME_IMPACT_DISTANCE_SQR) {
                PENDING_REPLACEMENTS.remove(source);
                return false;
            }
            return true;
        }
    }

    private static void trimPendingReplacements() {
        while (PENDING_REPLACEMENTS.size() >= MAX_PENDING_REPLACEMENTS) {
            Iterator<Entity> iterator = PENDING_REPLACEMENTS.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean finite(net.minecraft.world.phys.Vec3 position) {
        return position != null
                && Double.isFinite(position.f_82479_)
                && Double.isFinite(position.f_82480_)
                && Double.isFinite(position.f_82481_);
    }

    private record PendingReplacement(Level level, long gameTime,
                                      net.minecraft.world.phys.Vec3 position) {
    }

}
