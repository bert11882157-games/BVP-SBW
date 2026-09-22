package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;

abstract class BvpAnimatedParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    protected BvpAnimatedParticle(ClientLevel level, double x, double y, double z,
                                  double xSpeed, double ySpeed, double zSpeed,
                                  int lifetimeTicks, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.f_107225_ = lifetimeTicks;
        this.f_107226_ = 0.0F;
        this.f_107219_ = false;
        this.f_107215_ = xSpeed;
        this.f_107216_ = ySpeed;
        this.f_107217_ = zSpeed;
        this.f_107227_ = 1.0F;
        this.f_107228_ = 1.0F;
        this.f_107229_ = 1.0F;
        this.f_107230_ = 1.0F;
        this.m_108335_(sprites);
    }

    @Override
    public final void m_5989_() {
        super.m_5989_();
        if (this.f_107220_) {
            return;
        }
        updateParticle();
        this.m_108339_(this.sprites);
    }

    protected abstract void updateParticle();

    @FunctionalInterface
    protected interface ParticleFactory {
        Particle create(ClientLevel level, double x, double y, double z,
                        double xSpeed, double ySpeed, double zSpeed, SpriteSet sprites);
    }

    protected static class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final ParticleFactory factory;

        protected Provider(SpriteSet sprites, ParticleFactory factory) {
            this.sprites = sprites;
            this.factory = factory;
        }

        @Override
        public final Particle m_6966_(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                     double xSpeed, double ySpeed, double zSpeed) {
            return this.factory.create(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}

abstract class BvpFullBrightAnimatedParticle extends BvpAnimatedParticle {
    private static final int FULL_BRIGHT_LIGHT = 15728880;

    protected BvpFullBrightAnimatedParticle(ClientLevel level, double x, double y, double z,
                                             double xSpeed, double ySpeed, double zSpeed,
                                             int lifetimeTicks, SpriteSet sprites) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed, lifetimeTicks, sprites);
    }

    @Override
    public final int m_6355_(float partialTick) {
        return FULL_BRIGHT_LIGHT;
    }

    @Override
    public final ParticleRenderType m_7556_() {
        return ParticleRenderType.f_107432_;
    }
}
