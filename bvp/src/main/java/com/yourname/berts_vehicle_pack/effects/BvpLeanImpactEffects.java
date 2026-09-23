package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.effect.TransientLights;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;


public final class BvpLeanImpactEffects {
    private static final int ERA_LIGHT_LUMINANCE = 15;
    private static final int ERA_LIGHT_TICKS = 4;
    private static final int MAX_S8KO_PARTICLES_PER_TICK = 24;
    private static final ParticleOptions LARGE_SMOKE = ParticleTypes.f_123755_;
    /** Short-lived impact puff; the long-lived large-smoke particle is reserved for sustained effects. */
    private static final ParticleOptions IMPACT_SMOKE = ParticleTypes.f_123759_;
    private static final ParticleOptions SHORT_SPARK = ParticleTypes.f_175830_;
    private static long s8koParticleBudgetTick = Long.MIN_VALUE;
    private static int s8koParticlesUsed;

    private BvpLeanImpactEffects() {
    }

    public static void spawn(Level level, Vec3 position) {
        spawnMediumExplosion(level, position);
    }

    public static void spawn(Level level, Vec3 position, Entity source) {
        spawnMediumExplosion(level, position, source);
    }

    public static void spawnImpactSmoke(Level level, Vec3 position, Vec3 normal) {
        if (!(level instanceof ServerLevel server) || position == null || normal == null) return;
        Vec3 point = position.add(normal.scale(0.06D));
        ParticleTool.sendParticle(server, IMPACT_SMOKE, point.x, point.y, point.z,
                3, 0.09D, 0.06D, 0.09D, 0.012D, true);
    }

    public static void spawnTinyExplosion(Level level, Vec3 position) {
        spawnTinyExplosion(level, position, null);
    }

    public static void spawnTinyExplosion(Level level, Vec3 position, Entity source) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
        if (com.atsuishio.superbwarfare.api.effect.MissilePresentation.impact(level, position, source, missileRadius(source))) return;
        if (emitCaliberBurst(serverLevel, position, source)) return;
        ImpactBurstPolicy policy = ImpactBurstPolicy.forSource(source);
        int explosionCount = policy.s8ko() ? reserveS8koParticles(serverLevel.m_46467_(), 1) : 1;
        int smokeCount = policy.s8ko() ? reserveS8koParticles(serverLevel.m_46467_(), 3) : 3;
        if (explosionCount > 0) {
            ParticleTool.sendParticle(serverLevel, explosionParticle(), position.f_82479_, position.f_82480_,
                    position.f_82481_, explosionCount, 0.04D, 0.04D, 0.04D, 0.0D, true);
        }
        if (smokeCount > 0) {
            ParticleTool.sendParticle(serverLevel, IMPACT_SMOKE, position.f_82479_, position.f_82480_,
                    position.f_82481_, smokeCount, 0.12D, 0.12D, 0.12D, 0.01D, true);
        }
        TransientLights.spawn(level, position, ERA_LIGHT_LUMINANCE, ERA_LIGHT_TICKS);
    }

    public static void spawnSmallExplosion(Level level, Vec3 position) {
        if (level instanceof ServerLevel serverLevel && position != null) {
            spawnExplosionBurst(serverLevel, position, 2, 0.16D, 6, 0.32D, 0.45D, 0.025D, 14, 4,
                    ImpactBurstPolicy.DEFAULT);
        }
    }

    public static void spawnMediumExplosion(Level level, Vec3 position) {
        spawnMediumExplosion(level, position, null);
    }

    public static void spawnMediumExplosion(Level level, Vec3 position, Entity source) {
        spawnMediumExplosion(level, position, source, 1.0D);
    }

    /** Visual-only scale for typed component explosions; it never changes gameplay damage. */
    public static void spawnMediumExplosion(Level level, Vec3 position, Entity source,
                                            double presentationScale) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
        if (com.atsuishio.superbwarfare.api.effect.MissilePresentation.impact(level, position, source, missileRadius(source))) return;
        if (emitCaliberBurst(serverLevel, position, source)) return;
        ImpactBurstPolicy policy = ImpactBurstPolicy.forSource(source);
        double scale = safePresentationScale(presentationScale);

        if (isWaterImpact(level, position)) {
            int waterParticles = policy.s8ko()
                    ? reserveS8koParticles(serverLevel.m_46467_(), 8)
                    : scaledParticleCount(28, scale);
            if (waterParticles > 0) {
                ParticleTool.sendParticle(serverLevel, ParticleTypes.f_123804_, position.f_82479_, position.f_82480_,
                        position.f_82481_, waterParticles, 0.8D * scale, 1.8D * scale,
                        0.8D * scale, 0.45D * scale, true);
            }
            TransientLights.spawn(level, position, scaledLightLevel(13, scale), 4);
            return;
        }

        spawnExplosionBurst(serverLevel, position, 4, 0.35D, 12, 0.55D, 0.8D, 0.04D, 15, 5,
                policy, scale);
    }

    public static void spawnLargeExplosion(Level level, Vec3 position) {
        spawnLargeExplosion(level, position, null);
    }

    private static float missileRadius(Entity source) {
        return source != null && source.getBbWidth() >= .3F ? 6F : 3F;
    }

    public static void spawnLargeExplosion(Level level, Vec3 position, Entity source) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
        if (com.atsuishio.superbwarfare.api.effect.MissilePresentation.impact(level, position, source, missileRadius(source))) return;
        ImpactBurstPolicy policy = ImpactBurstPolicy.forSource(source);
        if (isWaterImpact(level, position)) {
            int waterParticles = policy.s8ko()
                    ? reserveS8koParticles(serverLevel.m_46467_(), 8)
                    : 48;
            if (waterParticles > 0) {
                ParticleTool.sendParticle(serverLevel, ParticleTypes.f_123804_, position.f_82479_, position.f_82480_,
                        position.f_82481_, waterParticles, 1.2D, 2.2D, 1.2D, 0.55D, true);
            }
            TransientLights.spawn(level, position, 15, 6);
            return;
        }
        spawnExplosionBurst(serverLevel, position, 8, 0.75D, 24, 1.0D, 1.4D, 0.06D, 15, 6,
                policy);
    }

    public static void spawnAmmoRackExplosion(Level level, Vec3 position) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
        ParticleTool.sendParticle(serverLevel, explosionParticle(), position.f_82479_, position.f_82480_,
                position.f_82481_, 20, 1.5D, 1.5D, 1.5D, 1.0D, true);
        ParticleTool.sendParticle(serverLevel, LARGE_SMOKE, position.f_82479_, position.f_82480_ + 0.8D,
                position.f_82481_, 64, 2.4D, 3.2D, 2.4D, 0.10D, true);
        ParticleTool.sendParticle(serverLevel, SHORT_SPARK, position.f_82479_, position.f_82480_,
                position.f_82481_, 40, 2.0D, 1.4D, 2.0D, 0.75D, true);
    }

    /**
     * Legacy client-only visual adapter. Server calls are inert; accepted server impacts
     * publish a compact recipe separately. The legacy seed does not synchronize client RNG.
     */
    @Deprecated
    public static void spawnImpactShrapnel(Level level, Projectile source, Vec3 position, Vec3 incomingDirection,
                                          Vec3 surfaceNormal, int count, float presentationScale, long seed) {
        if (level == null || !level.f_46443_) return;
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.yourname.berts_vehicle_pack.client.BvpClientImpactFragments
                        .acceptLegacy(level, position, incomingDirection,
                                surfaceNormal, count, presentationScale));
    }

    private static void spawnExplosionBurst(ServerLevel level, Vec3 position,
                                            int explosionCount, double explosionSpread,
                                            int smokeCount, double smokeSpread, double smokeVerticalSpread,
                                            double smokeSpeed, int lightLevel, int lightTicks,
                                            ImpactBurstPolicy policy) {
        spawnExplosionBurst(level, position, explosionCount, explosionSpread, smokeCount,
                smokeSpread, smokeVerticalSpread, smokeSpeed, lightLevel, lightTicks, policy, 1.0D);
    }

    private static void spawnExplosionBurst(ServerLevel level, Vec3 position,
                                            int explosionCount, double explosionSpread,
                                            int smokeCount, double smokeSpread, double smokeVerticalSpread,
                                            double smokeSpeed, int lightLevel, int lightTicks,
                                            ImpactBurstPolicy policy, double presentationScale) {
        double scale = safePresentationScale(presentationScale);
        if (policy.s8ko()) {
            // Preserve one visible initial blast and a small short-lived puff, while bounding
            // all S-8KO impacts together rather than applying only a per-impact limit.
            explosionCount = reserveS8koParticles(level.m_46467_(), Math.min(1, explosionCount));
            smokeCount = reserveS8koParticles(level.m_46467_(), Math.min(3, smokeCount));
        } else {
            explosionCount = scaledParticleCount(explosionCount, scale);
            smokeCount = scaledParticleCount(smokeCount, scale);
        }
        if (explosionCount > 0) {
            ParticleTool.sendParticle(level, explosionParticle(), position.f_82479_, position.f_82480_, position.f_82481_,
                    explosionCount, explosionSpread * scale, explosionSpread * scale,
                    explosionSpread * scale, scale, true);
        }
        if (smokeCount > 0) {
            ParticleTool.sendParticle(level, IMPACT_SMOKE, position.f_82479_, position.f_82480_, position.f_82481_,
                    smokeCount, smokeSpread * scale, smokeVerticalSpread * scale,
                    smokeSpread * scale, smokeSpeed * scale, true);
        }
        TransientLights.spawn(level, position, scaledLightLevel(lightLevel, scale), lightTicks);
    }

    private static double safePresentationScale(double value) {
        return Double.isFinite(value) && value > 0.0D ? value : 1.0D;
    }

    private static int scaledParticleCount(int base, double scale) {
        return Math.max(1, (int) Math.ceil(Math.max(0, base) * scale));
    }

    private static int scaledLightLevel(int base, double scale) {
        return Math.max(1, (int) Math.round(Math.max(0, base) * scale));
    }

    private static int reserveS8koParticles(long gameTime, int requested) {
        if (requested <= 0) {
            return 0;
        }
        if (s8koParticleBudgetTick != gameTime) {
            s8koParticleBudgetTick = gameTime;
            s8koParticlesUsed = 0;
        }
        int available = Math.max(0, MAX_S8KO_PARTICLES_PER_TICK - s8koParticlesUsed);
        int granted = Math.min(requested, available);
        s8koParticlesUsed += granted;
        return granted;
    }

    private static ParticleOptions explosionParticle() {
        return ModParticles.EXPLOSION.get();
    }

    /** Returns true for a handled shell/bullet, including intentionally suppressed sub-14.5mm FX. */
    private static boolean emitCaliberBurst(ServerLevel level, Vec3 position, Entity source) {
        if (source == null) return false;
        var combat = ProjectileProfiles.combatDescriptor(source);
        if (combat == null || combat.getCaliberMm() == null) return false;
        String munition = combat.getMunitionType() == null ? "" : combat.getMunitionType().m_135815_();
        if (combat.getHullDamageClass() == com.atsuishio.superbwarfare.api.projectile.ProjectileHullDamageClass.ATGM
                || munition.contains("rocket") || munition.contains("missile") || munition.contains("atgm")
                || munition.contains("bomb")) return false;
        // VOG-30 is an explosive grenade, not the tiny same-caliber cannon impact.
        float diameter = BvpProjectileEffectDefinition.isTypedAgs30Profile(ProjectileProfiles.resolve(source))
                ? 1.3F : BvpCaliberExplosion.diameter(combat.getCaliberMm());
        if (diameter <= 0F) return true;
        if (diameter > 2F && com.atsuishio.superbwarfare.api.effect.MissilePresentation.effect(
                level, position, diameter * .5F, false)) return true;
        ParticleTool.sendParticle(level,
                new com.yourname.berts_vehicle_pack.particle.SizedExplosionParticleOptions(diameter),
                position.f_82479_, position.f_82480_, position.f_82481_, 1, 0, 0, 0, 0, true);
        // Tiny rounds do not create a full-size smoke cloud or a terrain light source.
        return true;
    }

    private static boolean isWaterImpact(Level level, Vec3 position) {
        BlockPos blockPos = BlockPos.m_274561_(position.f_82479_, position.f_82480_, position.f_82481_);
        return level.m_8055_(blockPos).m_60713_(Blocks.f_49990_);
    }

    private enum ImpactBurstPolicy {
        DEFAULT(false),
        S8KO(true);

        private final boolean s8ko;

        ImpactBurstPolicy(boolean s8ko) {
            this.s8ko = s8ko;
        }

        boolean s8ko() {
            return this.s8ko;
        }

        static ImpactBurstPolicy forSource(Entity source) {
            if (source == null) {
                return DEFAULT;
            }
            var descriptor = ProjectileProfiles.combatDescriptor(source);
            String munition = descriptor == null || descriptor.getMunitionType() == null
                    ? "" : descriptor.getMunitionType().toString().toLowerCase(java.util.Locale.ROOT);
            Double caliber = descriptor == null ? null : descriptor.getCaliberMm();
            return (munition.endsWith(":rocket") || "rocket".equals(munition))
                    && caliber != null && Double.isFinite(caliber) && caliber <= 80.0D ? S8KO : DEFAULT;
        }
    }
}
