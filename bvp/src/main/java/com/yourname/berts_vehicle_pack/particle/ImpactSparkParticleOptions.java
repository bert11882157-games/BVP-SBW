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

/**
 * Networked diagnostic payload for one direct client particle probe. Accepted-impact shrapnel
 * uses real SBW projectile entities instead of this decorative particle.
 * The release runtime exposes the ParticleOptions writeToNetwork hook as m_7711_.
 */
public final class ImpactSparkParticleOptions implements ParticleOptions {
    public static final Codec<ImpactSparkParticleOptions> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.FLOAT.fieldOf("scale").forGetter(ImpactSparkParticleOptions::scale),
                    Codec.INT.fieldOf("lifetime").forGetter(ImpactSparkParticleOptions::lifetimeTicks))
                    .apply(instance, ImpactSparkParticleOptions::new));

    @SuppressWarnings("deprecation")
    public static final Deserializer<ImpactSparkParticleOptions> DESERIALIZER =
            new Deserializer<>() {
                @Override
                public ImpactSparkParticleOptions m_5739_(ParticleType<ImpactSparkParticleOptions> type,
                                                          StringReader reader) throws CommandSyntaxException {
                    reader.expect(' ');
                    float scale = reader.readFloat();
                    reader.expect(' ');
                    int lifetime = reader.readInt();
                    return new ImpactSparkParticleOptions(scale, lifetime);
                }

                @Override
                public ImpactSparkParticleOptions m_6507_(ParticleType<ImpactSparkParticleOptions> type,
                                                          FriendlyByteBuf buffer) {
                    return new ImpactSparkParticleOptions(buffer.readFloat(), buffer.readInt());
                }
            };

    private final float scale;
    private final int lifetimeTicks;

    public ImpactSparkParticleOptions(float scale, int lifetimeTicks) {
        this.scale = finite(scale) && scale > 0.0F ? Math.min(1.0F, scale) : 0.1F;
        this.lifetimeTicks = Math.max(1, Math.min(32, lifetimeTicks));
    }

    public float scale() {
        return scale;
    }

    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    @Override
    public ParticleType<?> m_6012_() {
        return ModParticles.IMPACT_SPARK.get();
    }

    @Override
    public void m_7711_(FriendlyByteBuf buffer) {
        buffer.writeFloat(scale);
        buffer.writeInt(lifetimeTicks);
    }

    @Override
    public String m_5942_() {
        return ForgeRegistries.PARTICLE_TYPES.getKey(m_6012_())
                + " [" + scale + ", " + lifetimeTicks + "]";
    }

    private static boolean finite(float value) {
        return Float.isFinite(value);
    }
}
