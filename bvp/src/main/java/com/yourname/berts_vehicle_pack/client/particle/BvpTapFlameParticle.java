package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;

/** API port of TaP EntityFMFlame; the bounded emitter also creates its companion FMSmoke. */
public final class BvpTapFlameParticle extends BvpAnimatedParticle {
    private float presentationScale = 1F;

    public void setDiameter(float diameter) {
        presentationScale = Math.max(0.001F, diameter / 1.2F);
        updateParticle();
    }
    private BvpTapFlameParticle(ClientLevel level, double x, double y, double z,
                                double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z, vx, vy, vz, 6, sprites);
        f_107219_ = true;
        f_172258_ = 1F;
        updateParticle();
    }
    @Override protected void updateParticle() {
        f_107663_ = presentationScale * Math.max(0F, 0.6F - f_107224_ * 0.1F);
        f_107227_ = 1F;
        f_107228_ = f_107229_ = Math.max(0F, 1F - f_107224_ * 0.2F);
        f_107230_ = Math.max(0F, 1F - f_107224_ * 0.1F);
        if (f_107218_) m_107274_();
    }
    @Override public int m_6355_(float partialTick) { return 15728880; }
    @Override public ParticleRenderType m_7556_() { return BvpTapParticleMaterial.INSTANCE; }
    public static final class Provider extends BvpAnimatedParticle.Provider {
        public Provider(SpriteSet sprites) { super(sprites, BvpTapFlameParticle::new); }
    }
}
