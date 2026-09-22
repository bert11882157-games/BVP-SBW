package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;

/** Port of supplied Flans/TaP EntityAfterburn: ValkEx texture, six-tick life and original fades.
 * See THIRD_PARTY_AFTERBURNER.md for source and texture provenance. */
public final class BvpAfterburnerParticle extends BvpFullBrightAnimatedParticle {
    private float presentationScale = 1F;

    public void setDiameter(float diameter) {
        presentationScale = Math.max(0.001F, diameter / 1.6F);
        updateParticle();
    }
    private BvpAfterburnerParticle(ClientLevel level, double x, double y, double z,
                                   double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z, vx, vy, vz, 6, sprites);
        f_107219_ = true;
        f_172258_ = 1F;
        updateParticle();
    }
    @Override protected void updateParticle() {
        f_107663_ = presentationScale * Math.max(0F, 0.8F - f_107224_ * 0.13F);
        f_107227_ = f_107228_ = Math.max(0F, 1F - f_107224_ * 0.2F);
        f_107229_ = 1F;
        f_107230_ = Math.max(0F, 1F - f_107224_ * 0.1F);
        if (f_107218_) m_107274_();
    }
    @Override public net.minecraft.client.particle.ParticleRenderType m_7556_() {
        return BvpTapParticleMaterial.INSTANCE;
    }
    public static final class Provider extends BvpAnimatedParticle.Provider {
        public Provider(SpriteSet sprites) { super(sprites, BvpAfterburnerParticle::new); }
    }
}
