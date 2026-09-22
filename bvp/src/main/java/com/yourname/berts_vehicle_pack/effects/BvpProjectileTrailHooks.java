package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.tools.ExplosionFxContext;
import com.atsuishio.superbwarfare.tools.ParticleTool;
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

    private BvpProjectileTrailHooks() {
    }

    public static void registerExplosionFxHandler() {
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
        if (consumeDeferredExplosionPresentation(level, directSource, context.getParticlePosition())) {
            return true;
        }
        BvpProjectileEffectDefinition definition = BvpProjectileEffectDefinition.forEntity(directSource);
        if (definition == null || "none".equals(definition.impact().explosion())) {
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
