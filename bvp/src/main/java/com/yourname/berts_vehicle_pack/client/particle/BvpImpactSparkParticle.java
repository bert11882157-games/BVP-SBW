package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SpriteSet;
import com.yourname.berts_vehicle_pack.particle.ImpactSparkParticleOptions;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BvpImpactSparkParticle extends BvpFullBrightAnimatedParticle {
    private static final float BASE_SIZE = 0.10F;
    private static final float GRAVITY = 0.12F;
    private static final double DRAG = 0.78D;

    private BvpImpactSparkParticle(ClientLevel level, double x, double y, double z,
                                   double xSpeed, double ySpeed, double zSpeed,
                                   ImpactSparkParticleOptions options, SpriteSet sprites) {
        super(level, x, y, z, xSpeed, ySpeed, zSpeed,
                options.lifetimeTicks(), sprites);
        this.baseSize = BASE_SIZE * options.scale();
        if (EliteDiagnostics.isClientEnabled()) {
            EliteDiagnostics.recordClient(level.m_46467_(), "effects", "spark_created",
                    "x", x, "y", y, "z", z, "vx", xSpeed, "vy", ySpeed, "vz", zSpeed,
                    "size", baseSize, "lifetime_ticks", options.lifetimeTicks());
        }
        this.f_107226_ = GRAVITY;
        this.f_107663_ = this.baseSize;
    }

    // The incoming velocity is the server-selected diagnostic vector; the payload supplies
    // size and lifetime so the probe does not create divergent local sparks.
    private final float baseSize;

    @Override
    protected void updateParticle() {
        this.f_107215_ *= DRAG;
        this.f_107216_ *= DRAG;
        this.f_107217_ *= DRAG;
        float remaining = 1.0F - this.f_107224_ / (float) this.f_107225_;
        this.f_107663_ = this.baseSize * Math.max(0.35F, remaining);
        this.f_107230_ = Math.max(0.0F, remaining);
    }

    public static final class Provider implements ParticleProvider<ImpactSparkParticleOptions> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle m_6966_(ImpactSparkParticleOptions options, ClientLevel level,
                               double x, double y, double z,
                               double xSpeed, double ySpeed, double zSpeed) {
            return new BvpImpactSparkParticle(level, x, y, z, xSpeed, ySpeed, zSpeed,
                    options, sprites);
        }
    }
}
