package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import com.yourname.berts_vehicle_pack.particle.ProjectileEffectParticleOptions;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.core.particles.ParticleType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class BvpRocketFlameParticle extends BvpFullBrightAnimatedParticle {
    private static final double BASE_SIZE = 0.62D;
    private static final double MIN_SIZE_SCALE = 0.005D;
    private static final double MIN_RANDOM_SCALE = 0.8D;
    private static final float MIN_GREEN = 0.22F;
    private static final float MIN_BLUE = 0.02F;

    private final float baseSize;

    protected BvpRocketFlameParticle(ClientLevel level, double x, double y, double z,
                                     ProjectileEffectParticleOptions options, SpriteSet sprites) {
        super(level, x, y, z, options.velocityX(), options.velocityY(), options.velocityZ(),
                options.lifetimeTicks(), sprites);
        this.baseSize = (float) (BASE_SIZE * Math.max(MIN_SIZE_SCALE, options.scale())
                * Math.max(MIN_RANDOM_SCALE, options.randomScale())
                * Math.max(0.1D, options.widthBlocks() * 10.0D));
        this.f_107663_ = this.baseSize;
    }

    @Override
    protected void updateParticle() {
        float t = this.f_107224_ / (float) this.f_107225_;
        this.f_107663_ = this.baseSize * Math.max(0.0F, 1.0F - t);
        this.f_107230_ = Math.max(0.0F, 1.0F - t);
        this.f_107227_ = 1.0F;
        this.f_107228_ = Math.max(MIN_GREEN, 1.0F - 0.65F * t);
        this.f_107229_ = Math.max(MIN_BLUE, 1.0F - 0.95F * t);
    }

    public static class Provider implements ParticleProvider<ProjectileEffectParticleOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle m_6966_(ProjectileEffectParticleOptions options, ClientLevel level, double x, double y,
                                double z, double xSpeed, double ySpeed, double zSpeed) {
            return new BvpRocketFlameParticle(level, x, y, z, options, sprites);
        }
    }
}
