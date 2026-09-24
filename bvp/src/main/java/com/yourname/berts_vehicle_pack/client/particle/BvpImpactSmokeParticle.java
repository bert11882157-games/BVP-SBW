package com.yourname.berts_vehicle_pack.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;

/** Soft TaP smoke sprite with restrained lift, expansion and no terrain/network ownership. */
public final class BvpImpactSmokeParticle extends BvpAnimatedParticle {
    private static ClientLevel budgetLevel;
    private static long budgetTick = Long.MIN_VALUE;
    private static int impactCount, wreckCount;
    private static BvpImpactSmokeParticle create(ClientLevel level,double x,double y,double z,
            double vx,double vy,double vz,SpriteSet sprites,boolean wreck) {
        long tick=level.m_46467_();
        if(budgetLevel!=level||budgetTick!=tick){budgetLevel=level;budgetTick=tick;impactCount=0;wreckCount=0;}
        if(wreck ? wreckCount++>=24 : impactCount++>=64)return null;
        return new BvpImpactSmokeParticle(level,x,y,z,vx,vy,vz,sprites,wreck);
    }
    private float diameter = 1.0f;
    private final boolean wreck;
    private final com.atsuishio.superbwarfare.client.particle.DistantParticleLight distantLight =
            new com.atsuishio.superbwarfare.client.particle.DistantParticleLight();
    @Override public int m_6355_(float partialTick) {
        return distantLight.sample(f_107208_, f_107212_, f_107213_ + 1, f_107214_);
    }
    public void setDiameter(float diameter) { this.diameter = Math.max(.01f, diameter); updateParticle(); }
    private BvpImpactSmokeParticle(ClientLevel level, double x, double y, double z,
            double vx, double vy, double vz, SpriteSet sprites, boolean wreck) {
        super(level, x, y, z, vx, vy, vz, wreck ? 64 : 28, sprites);
        this.wreck = wreck;
        f_172258_ = wreck ? .975f : .96f;
        f_107226_ = wreck ? -.06f : -.008f;
        updateParticle();
    }
    @Override protected void updateParticle() {
        float t = Math.min(1, f_107224_ / (float) f_107225_);
        f_107663_ = diameter * .5f * (.65f + t * 1.25f);
        f_107227_ = f_107228_ = f_107229_ = wreck ? .23f : .48f;
        f_107230_ = (wreck ? .64f : .60f) * (1 - t) * (1 - t);
    }
    @Override public ParticleRenderType m_7556_() { return ParticleRenderType.f_107431_; }
    public static final class Provider extends BvpAnimatedParticle.Provider {
        public Provider(SpriteSet sprites) { super(sprites, (l,x,y,z,vx,vy,vz,s) -> create(l,x,y,z,vx,vy,vz,s,false)); }
    }
    public static final class WreckProvider extends BvpAnimatedParticle.Provider {
        public WreckProvider(SpriteSet sprites) { super(sprites, (l,x,y,z,vx,vy,vz,s) -> create(l,x,y,z,vx,vy,vz,s,true)); }
    }
}
