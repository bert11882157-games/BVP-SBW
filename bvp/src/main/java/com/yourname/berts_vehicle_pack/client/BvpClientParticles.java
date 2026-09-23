package com.yourname.berts_vehicle_pack.client;

import com.yourname.berts_vehicle_pack.client.particle.BvpImpactSparkParticle;
import com.yourname.berts_vehicle_pack.client.particle.BvpExplosionParticle;
import com.yourname.berts_vehicle_pack.client.particle.BvpSizedExplosionParticle;
import com.yourname.berts_vehicle_pack.client.particle.BvpAfterburnerParticle;
import com.yourname.berts_vehicle_pack.client.particle.BvpRocketFlameParticle;
import com.yourname.berts_vehicle_pack.client.particle.BvpRocketSmokeParticle;
import com.yourname.berts_vehicle_pack.effects.BvpProjectileEffectDefinition;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import com.yourname.berts_vehicle_pack.particle.ProjectileEffectParticleOptions;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraftforge.client.event.RegisterParticleProvidersEvent;

public final class BvpClientParticles {
    private BvpClientParticles() {
    }

    /** Reuses the aircraft afterburner material, texture and fades at the missile's body size. */
    public static void spawnMissileExhaust(boolean flame, Vec3 position, float diameter) {
        var engine = net.minecraft.client.Minecraft.m_91087_().f_91061_;
        var particle = engine.m_107370_(flame ? ModParticles.TAP_EXHAUST_FLAME.get()
                        : ModParticles.TAP_EXHAUST_SMOKE.get(),
                position.f_82479_, position.f_82480_, position.f_82481_, 0, 0, 0);
        if (particle instanceof com.yourname.berts_vehicle_pack.client.particle.BvpTapFlameParticle afterburner)
            afterburner.setDiameter(diameter);
        if (particle instanceof com.yourname.berts_vehicle_pack.client.particle.BvpTapSmokeParticle smoke)
            smoke.setDiameter(diameter);
    }

    public static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.IMPACT_SMOKE.get(), com.yourname.berts_vehicle_pack.client.particle.BvpImpactSmokeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.WRECK_SMOKE.get(), com.yourname.berts_vehicle_pack.client.particle.BvpImpactSmokeParticle.WreckProvider::new);
        event.registerSpriteSet(ModParticles.ROCKET_FLAME.get(), BvpRocketFlameParticle.Provider::new);
        event.registerSpriteSet(ModParticles.ROCKET_SMOKE.get(), BvpRocketSmokeParticle.Provider::new);
        event.registerSpriteSet(ModParticles.IMPACT_SPARK.get(), BvpImpactSparkParticle.Provider::new);
        event.registerSpriteSet(ModParticles.EXPLOSION.get(), BvpExplosionParticle.Provider::new);
        event.registerSpriteSet(ModParticles.SIZED_EXPLOSION.get(), BvpSizedExplosionParticle.Provider::new);
        event.registerSpriteSet(ModParticles.AFTERBURNER.get(), BvpAfterburnerParticle.Provider::new);
        event.registerSpriteSet(ModParticles.TAP_EXHAUST_FLAME.get(),
                com.yourname.berts_vehicle_pack.client.particle.BvpTapFlameParticle.Provider::new);
        event.registerSpriteSet(ModParticles.TAP_EXHAUST_SMOKE.get(),
                com.yourname.berts_vehicle_pack.client.particle.BvpTapSmokeParticle.Provider::new);
    }

    public static void spawnProjectileTrail(ClientLevel level, BvpProjectileEffectDefinition.Trail trail,
                                            BvpProjectileEffectDefinition.Phase phase,
                                            double x, double y, double z,
                                            double randomScale, Vec3 velocity) {
        spawnProjectileTrail(level, trail, phase, x, y, z, randomScale, velocity, 1.0D);
    }

    /**
     * Emits one typed trail position with an optional central-stream size multiplier.  The
     * multiplier is supplied by the immutable effect definition so orbiting satellites cannot
     * accidentally inherit the central trail's presentation size.
     */
    public static void spawnProjectileTrail(ClientLevel level, BvpProjectileEffectDefinition.Trail trail,
                                            BvpProjectileEffectDefinition.Phase phase,
                                            double x, double y, double z,
                                            double randomScale, Vec3 velocity,
                                            double sizeMultiplier) {
        if (level == null || trail == null || phase == null || trail.isTracer()) {
            return;
        }
        if (!Double.isFinite(sizeMultiplier) || sizeMultiplier <= 0.0D) {
            return;
        }
        double sizeScale = phase.scale() * sizeMultiplier;
        if (!Double.isFinite(sizeScale) || sizeScale <= 0.0D) {
            return;
        }
        double inheritX = velocity == null ? 0.0D : velocity.f_82479_ * trail.velocityInheritance();
        double inheritY = velocity == null ? 0.0D : velocity.f_82480_ * trail.velocityInheritance();
        double inheritZ = velocity == null ? 0.0D : velocity.f_82481_ * trail.velocityInheritance();
        if (phase.flame() && trail.flame()) {
            spawnTrailParticle(level, true,
                    x, y, z, sizeScale, randomScale, trail.widthBlocks(),
                    inheritX, inheritY, inheritZ, trail.flameLifetimeTicks());
        }
        if (phase.smoke() && trail.smoke()) {
            spawnTrailParticle(level, false,
                    x, y, z, sizeScale, randomScale, trail.widthBlocks(),
                    inheritX, inheritY, inheritZ, trail.smokeLifetimeTicks());
        }
    }

    private static void spawnTrailParticle(ClientLevel level, boolean flame,
                                           double x, double y, double z,
                                           double sizeScale, double randomScale, double width,
                                           double velocityX, double velocityY, double velocityZ,
                                           int lifetime) {
        // Force trail visibility within the renderer's bounded budget. Shared flame and smoke
        // particle types retain their normal limiter behavior outside this path.
        ProjectileEffectParticleOptions options = new ProjectileEffectParticleOptions(flame,
                (float) sizeScale, (float) randomScale, lifetime, (float) width,
                (float) velocityX, (float) velocityY, (float) velocityZ);
        level.m_6493_(options, true, x, y, z, velocityX, velocityY, velocityZ);
    }
}
