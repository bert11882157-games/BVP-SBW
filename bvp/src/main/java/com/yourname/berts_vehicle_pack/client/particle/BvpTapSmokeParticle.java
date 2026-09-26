package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;

/** TaP EntityFMSmoke companion: 16 ticks, constant horizontal motion and +0.01 vertical acceleration. */
public final class BvpTapSmokeParticle extends BvpAnimatedParticle {
    private float presentationScale = 1F;
    private float linger = 1F;

    /** Stretches the puff's life (and fade) by [factor]: missile trails hang in the air longer than engine haze. */
    public void setLinger(float factor) {
        linger = Math.max(1F, factor);
        f_107225_ = Math.round(16 * linger);
        updateParticle();
    }

    public void setDiameter(float diameter) {
        presentationScale = Math.max(0.001F, diameter / 0.2F);
        updateParticle();
    }
    private BvpTapSmokeParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z, vx, vy, vz, 16, sprites);
        f_107219_ = true;
        f_172258_ = 1F;
        f_107226_ = -0.25F; // Particle.tick applies -0.04 * gravity = TaP's +0.01.
        updateParticle();
    }
    @Override protected void updateParticle() {
        f_107663_ = presentationScale * (0.1F + f_107224_ * 0.01F / linger);
        f_107227_ = f_107228_ = f_107229_ = 0.5F;
        f_107230_ = Math.max(0F, 1F - f_107224_ * 0.1F / linger);
        if (f_107218_) m_107274_();
    }
    @Override public ParticleRenderType m_7556_() { return BvpTapParticleMaterial.INSTANCE; }
    public static final class Provider extends BvpAnimatedParticle.Provider {
        public Provider(SpriteSet sprites) { super(sprites, BvpTapSmokeParticle::new); }
    }
}
