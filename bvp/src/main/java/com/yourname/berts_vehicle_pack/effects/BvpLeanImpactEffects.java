package com.yourname.berts_vehicle_pack.effects;

import com.atsuishio.superbwarfare.tools.ParticleTool;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.item.gun.ProjectileFactory;
import com.atsuishio.superbwarfare.api.effect.TransientLights;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

public final class BvpLeanImpactEffects {
    private static final int ERA_LIGHT_LUMINANCE = 15;
    private static final int ERA_LIGHT_TICKS = 4;
    private static final double MIN_DIRECTION_SQR = 1.0E-6D;
    private static final double IMPACT_STREAK_MIN_SPEED = 0.85D;
    private static final double IMPACT_STREAK_MAX_SPEED = 1.65D;
    private static final int IMPACT_STREAK_MIN_LIFETIME = 5;
    private static final int IMPACT_STREAK_MAX_LIFETIME_EXCLUSIVE = 8;
    private static final float IMPACT_SHRAPNEL_DAMAGE = 1.0F;
    private static final int MAX_S8KO_PARTICLES_PER_TICK = 24;
    /** Existing generated 7.62 mm bullet profile with a validated tracer_v2 payload. */
    private static final ResourceLocation IMPACT_SHRAPNEL_PROFILE =
            new ResourceLocation(BertsVehiclePack.MODID, "bmp2/mainmachinegun/belt_ammo_00_russian_762_ap_t");
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
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

    public static void spawnTinyExplosion(Level level, Vec3 position) {
        spawnTinyExplosion(level, position, null);
    }

    public static void spawnTinyExplosion(Level level, Vec3 position, Entity source) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
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

    public static void spawnLargeExplosion(Level level, Vec3 position, Entity source) {
        if (!(level instanceof ServerLevel serverLevel) || position == null) {
            return;
        }
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
     * Emits the accepted-impact tracer fan.  The server chooses every direction from the
     * immutable impact seed, so observers receive identical streak vectors.  Incidence is
     * blended continuously: shallow hits retain a reflected forward fan while perpendicular
     * hits become a tangent-plane pancake around the supplied surface normal.
     */
    public static void spawnImpactShrapnel(Level level, Projectile source, Vec3 position, Vec3 incomingDirection,
                                           Vec3 surfaceNormal, int count, float presentationScale,
                                           long seed) {
        if (!(level instanceof ServerLevel serverLevel) || source == null
                || position == null || count <= 0) {
            return;
        }
        if (!finite(incomingDirection) || incomingDirection.m_82556_() <= MIN_DIRECTION_SQR) {
            return;
        }

        Vec3 incoming = normalized(incomingDirection);
        Vec3 normal = normalized(surfaceNormal);
        Vec3 reflected = incoming.m_82546_(normal.m_82490_(2.0D * incoming.m_82526_(normal)));
        if (reflected.m_82526_(reflected) <= MIN_DIRECTION_SQR) {
            reflected = normal;
        } else {
            reflected = reflected.m_82541_();
        }

        Vec3 tangentU = orthogonal(normal);
        Vec3 tangentV = normal.m_82537_(tangentU).m_82541_();
        double incidence = Math.abs(incoming.m_82526_(normal));
        double pancakeWeight = smoothstep(0.25D, 0.92D, incidence);
        double safeScale = Double.isFinite(presentationScale) && presentationScale > 0.0F
                ? presentationScale : 0.1D;
        Random random = new Random(seed);
        Entity fragmentOwner = source.m_19749_() != null ? source.m_19749_() : source;

        for (int index = 0; index < count; index++) {
            double theta = random.nextDouble() * Math.PI * 2.0D;
            double fanSpread = 0.25D + random.nextDouble() * 0.55D;
            Vec3 fanDirection = reflected
                    .m_82549_(tangentU.m_82490_(Math.cos(theta) * fanSpread))
                    .m_82549_(tangentV.m_82490_(Math.sin(theta) * fanSpread))
                    .m_82541_();
            Vec3 pancakeDirection = tangentU.m_82490_(Math.cos(theta))
                    .m_82549_(tangentV.m_82490_(Math.sin(theta)))
                    .m_82549_(normal.m_82490_(0.06D))
                    .m_82541_();
            Vec3 direction = fanDirection.m_82490_(1.0D - pancakeWeight)
                    .m_82549_(pancakeDirection.m_82490_(pancakeWeight))
                    .m_82541_();
            // Keep the presentation on the outward side without snapping the blended direction.
            double outward = direction.m_82526_(normal);
            if (outward < 0.02D) {
                direction = direction.m_82549_(normal.m_82490_(0.02D - outward)).m_82541_();
            }

            double speed = IMPACT_STREAK_MIN_SPEED
                    + random.nextDouble() * (IMPACT_STREAK_MAX_SPEED - IMPACT_STREAK_MIN_SPEED);
            int lifetime = IMPACT_STREAK_MIN_LIFETIME
                    + random.nextInt(IMPACT_STREAK_MAX_LIFETIME_EXCLUSIVE - IMPACT_STREAK_MIN_LIFETIME);
            boolean spawned = ProjectileFactory.spawnImpactShrapnel(
                    serverLevel,
                    fragmentOwner,
                    position,
                    direction,
                    (float) speed,
                    lifetime,
                    IMPACT_SHRAPNEL_DAMAGE,
                    IMPACT_SHRAPNEL_PROFILE,
                    (float) safeScale,
                    seed ^ (0x9E3779B97F4A7C15L * (index + 1L)));
            if (EliteDiagnostics.isEnabled(level)) {
                EliteDiagnostics.record(source, "shrapnel", "spawn_result",
                        "index", index, "count", count, "spawned", spawned,
                        "profile", IMPACT_SHRAPNEL_PROFILE, "position", position,
                        "direction", direction, "speed_blocks_per_tick", speed,
                        "lifetime_ticks", lifetime, "scale", safeScale, "seed", seed);
            }
        }
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

    private static Vec3 normalized(Vec3 direction) {
        return !finite(direction) || direction.m_82556_() <= MIN_DIRECTION_SQR
                ? WORLD_UP
                : direction.m_82541_();
    }

    private static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.f_82479_)
                && Double.isFinite(value.f_82480_)
                && Double.isFinite(value.f_82481_);
    }

    private static Vec3 orthogonal(Vec3 direction) {
        Vec3 reference = Math.abs(direction.f_82480_) < 0.95D ? WORLD_UP : WORLD_X;
        Vec3 perpendicular = reference.m_82537_(direction);
        return perpendicular.m_82556_() <= MIN_DIRECTION_SQR
                ? WORLD_X
                : perpendicular.m_82541_();
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0D, Math.min(1.0D, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0D - 2.0D * t);
    }

    private static ParticleOptions explosionParticle() {
        return ModParticles.EXPLOSION.get();
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
