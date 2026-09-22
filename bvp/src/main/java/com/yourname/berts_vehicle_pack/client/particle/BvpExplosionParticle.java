package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.client.particle.SpriteSet;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.ThreadLocalRandom;

/** A BVP-owned explosion animation that never replaces Minecraft's global particle atlas. */
@OnlyIn(Dist.CLIENT)
public final class BvpExplosionParticle extends BvpFullBrightAnimatedParticle {
    /** Brief presentation-only impact flash; sustained smoke is a separate effect. */
    private static final int LIFETIME_TICKS = 5;
    private static final float MIN_SIZE = 1.5F;
    private static final float SIZE_VARIATION = 0.5F;
    private static final double MOTION_SCALE = 0.02D;

    private final float baseSize;

    private BvpExplosionParticle(ClientLevel level, double x, double y, double z,
                                 double xSpeed, double ySpeed, double zSpeed, SpriteSet sprites) {
        super(level, x, y, z, xSpeed * MOTION_SCALE, ySpeed * MOTION_SCALE, zSpeed * MOTION_SCALE,
                LIFETIME_TICKS, sprites);
        this.baseSize = MIN_SIZE + ThreadLocalRandom.current().nextFloat() * SIZE_VARIATION;
        if (EliteDiagnostics.isClientEnabled()) {
            EliteDiagnostics.recordClient(level.m_46467_(), "effects", "explosion_created",
                    "x", x, "y", y, "z", z, "size", baseSize, "lifetime_ticks", LIFETIME_TICKS);
        }
        this.f_107663_ = this.baseSize;
    }

    @Override
    protected void updateParticle() {
        float progress = this.f_107224_ / (float) this.f_107225_;
        this.f_107663_ = this.baseSize * (0.75F + 0.5F * progress);
        this.f_107230_ = Math.max(0.0F, 1.0F - progress * progress);
    }

    public static final class Provider extends BvpAnimatedParticle.Provider {
        public Provider(SpriteSet sprites) {
            super(sprites, BvpExplosionParticle::new);
        }
    }
}
