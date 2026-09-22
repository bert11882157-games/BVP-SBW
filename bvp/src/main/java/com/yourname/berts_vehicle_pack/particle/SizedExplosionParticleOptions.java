package com.yourname.berts_vehicle_pack.particle;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.yourname.berts_vehicle_pack.init.ModParticles;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.network.FriendlyByteBuf;

/** Visual diameter in blocks; independent of blast damage and radius. */
public record SizedExplosionParticleOptions(float diameter) implements ParticleOptions {
    public SizedExplosionParticleOptions {
        diameter = Float.isFinite(diameter) ? Math.max(0.01F, Math.min(32F, diameter)) : 0.01F;
    }
    public static final Codec<SizedExplosionParticleOptions> CODEC = Codec.FLOAT.xmap(
            SizedExplosionParticleOptions::new, SizedExplosionParticleOptions::diameter);
    public static final Deserializer<SizedExplosionParticleOptions> DESERIALIZER = new Deserializer<>() {
        @Override public SizedExplosionParticleOptions m_5739_(ParticleType<SizedExplosionParticleOptions> type,
                                                              StringReader reader) throws CommandSyntaxException {
            reader.expect(' ');
            return new SizedExplosionParticleOptions(reader.readFloat());
        }
        @Override public SizedExplosionParticleOptions m_6507_(ParticleType<SizedExplosionParticleOptions> type,
                                                              FriendlyByteBuf buffer) {
            return new SizedExplosionParticleOptions(buffer.readFloat());
        }
    };
    @Override public ParticleType<?> m_6012_() { return ModParticles.SIZED_EXPLOSION.get(); }
    @Override public void m_7711_(FriendlyByteBuf buffer) { buffer.writeFloat(diameter); }
    @Override public String m_5942_() { return "berts_vehicle_pack:sized_explosion " + diameter; }
}
