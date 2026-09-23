package com.yourname.berts_vehicle_pack.client.particle;

import com.yourname.berts_vehicle_pack.particle.SizedExplosionParticleOptions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;

/** One stationary, short-lived flash with an explicit maximum diameter. */
public final class BvpSizedExplosionParticle extends BvpFullBrightAnimatedParticle {
    private final float radius;
    private BvpSizedExplosionParticle(ClientLevel level, double x, double y, double z,
                                      float diameter, SpriteSet sprites) {
        super(level, x, y, z, 0, 0, 0, 5, sprites);
        radius = diameter * 0.5F;
        f_107663_ = radius;
    }
    @Override protected void updateParticle() {
        float progress = f_107224_ / (float) f_107225_;
        f_107663_ = radius;
        f_107230_ = Math.max(0F, 1F - progress * progress);
    }
    public static final class Provider implements ParticleProvider<SizedExplosionParticleOptions> {
        private final SpriteSet sprites;
        public Provider(SpriteSet sprites) { this.sprites = sprites; }
        @Override public Particle m_6966_(SizedExplosionParticleOptions options, ClientLevel level,
                                          double x, double y, double z, double vx, double vy, double vz) {
            // One proportionate soft puff per impact, emitted client-side rather than
            // adding a second server packet for every autocannon projectile.
            Particle smoke = net.minecraft.client.Minecraft.m_91087_().f_91061_.m_107370_(
                    com.yourname.berts_vehicle_pack.init.ModParticles.IMPACT_SMOKE.get(),x,y,z,0,.008,0);
            if (smoke instanceof BvpImpactSmokeParticle puff) {
                puff.setDiameter(options.diameter() * .6f);
                com.atsuishio.superbwarfare.client.FarEffectsClient.INSTANCE.retainParticle(puff);
            }
            return new BvpSizedExplosionParticle(level, x, y, z, options.diameter(), sprites);
        }
    }
}
