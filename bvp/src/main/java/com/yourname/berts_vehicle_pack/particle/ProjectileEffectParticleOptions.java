package com.yourname.berts_vehicle_pack.particle;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.registries.ForgeRegistries;

/** Immutable per-emission values carried by the v1 projectile trail particles. */
public final class ProjectileEffectParticleOptions implements ParticleOptions {
    public static final Codec<ProjectileEffectParticleOptions> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.BOOL.fieldOf("flame").forGetter(ProjectileEffectParticleOptions::flame),
                    Codec.FLOAT.fieldOf("scale").forGetter(ProjectileEffectParticleOptions::scale),
                    Codec.FLOAT.fieldOf("randomScale").forGetter(ProjectileEffectParticleOptions::randomScale),
                    Codec.INT.fieldOf("lifetime").forGetter(ProjectileEffectParticleOptions::lifetimeTicks),
                    Codec.FLOAT.fieldOf("width").forGetter(ProjectileEffectParticleOptions::widthBlocks),
                    Codec.FLOAT.fieldOf("velocityX").forGetter(ProjectileEffectParticleOptions::velocityX),
                    Codec.FLOAT.fieldOf("velocityY").forGetter(ProjectileEffectParticleOptions::velocityY),
                    Codec.FLOAT.fieldOf("velocityZ").forGetter(ProjectileEffectParticleOptions::velocityZ))
                    .apply(instance, ProjectileEffectParticleOptions::new));

    public static final Deserializer<ProjectileEffectParticleOptions> DESERIALIZER = new Deserializer<>() {
        @Override
        public ProjectileEffectParticleOptions m_5739_(ParticleType<ProjectileEffectParticleOptions> type,
                                                        StringReader reader) throws CommandSyntaxException {
            reader.expect(' ');
            boolean flame = reader.readBoolean(); reader.expect(' ');
            float scale = reader.readFloat(); reader.expect(' ');
            float random = reader.readFloat(); reader.expect(' ');
            int lifetime = reader.readInt(); reader.expect(' ');
            float width = reader.readFloat(); reader.expect(' ');
            float vx = reader.readFloat(); reader.expect(' ');
            float vy = reader.readFloat(); reader.expect(' ');
            float vz = reader.readFloat();
            return new ProjectileEffectParticleOptions(flame, scale, random, lifetime, width, vx, vy, vz);
        }

        @Override
        public ProjectileEffectParticleOptions m_6507_(ParticleType<ProjectileEffectParticleOptions> type,
                                                        FriendlyByteBuf buffer) {
            return new ProjectileEffectParticleOptions(buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(),
                    buffer.readInt(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        }
    };

    private final boolean flame;
    private final float scale;
    private final float randomScale;
    private final int lifetimeTicks;
    private final float widthBlocks;
    private final float velocityX;
    private final float velocityY;
    private final float velocityZ;

    public ProjectileEffectParticleOptions(boolean flame, float scale, float randomScale, int lifetimeTicks,
                                           float widthBlocks, float velocityX, float velocityY, float velocityZ) {
        this.flame = flame;
        this.scale = finitePositive(scale, 0.005F, 64.0F, 1.0F);
        this.randomScale = finitePositive(randomScale, 0.01F, 8.0F, 1.0F);
        this.lifetimeTicks = Math.max(1, Math.min(256, lifetimeTicks));
        this.widthBlocks = finitePositive(widthBlocks, 0.001F, 16.0F, 0.1F);
        this.velocityX = finite(velocityX) ? velocityX : 0.0F;
        this.velocityY = finite(velocityY) ? velocityY : 0.0F;
        this.velocityZ = finite(velocityZ) ? velocityZ : 0.0F;
    }

    public boolean flame() { return flame; }
    public float scale() { return scale; }
    public float randomScale() { return randomScale; }
    public int lifetimeTicks() { return lifetimeTicks; }
    public float widthBlocks() { return widthBlocks; }
    public float velocityX() { return velocityX; }
    public float velocityY() { return velocityY; }
    public float velocityZ() { return velocityZ; }

    @Override
    public ParticleType<?> m_6012_() {
        return flame ? ModParticles.ROCKET_FLAME.get() : ModParticles.ROCKET_SMOKE.get();
    }

    @Override
    public void m_7711_(FriendlyByteBuf buffer) {
        buffer.writeBoolean(flame);
        buffer.writeFloat(scale);
        buffer.writeFloat(randomScale);
        buffer.writeInt(lifetimeTicks);
        buffer.writeFloat(widthBlocks);
        buffer.writeFloat(velocityX);
        buffer.writeFloat(velocityY);
        buffer.writeFloat(velocityZ);
    }

    @Override
    public String m_5942_() {
        return ForgeRegistries.PARTICLE_TYPES.getKey(m_6012_()) + " [" + flame + "," + scale + "," + lifetimeTicks + "]";
    }

    private static float finitePositive(float value, float min, float max, float fallback) {
        return finite(value) && value >= min ? Math.min(max, value) : fallback;
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
