package com.yourname.berts_vehicle_pack.init;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.particle.ImpactSparkParticleOptions;
import com.yourname.berts_vehicle_pack.particle.ProjectileEffectParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.mojang.serialization.Codec;

public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, BertsVehiclePack.MODID);

    public static final RegistryObject<ParticleType<ProjectileEffectParticleOptions>> ROCKET_FLAME =
            PARTICLE_TYPES.register("rocket_flame", () -> new ParticleType<ProjectileEffectParticleOptions>(
                    false, ProjectileEffectParticleOptions.DESERIALIZER) {
                @Override
                public Codec<ProjectileEffectParticleOptions> m_7652_() {
                    return ProjectileEffectParticleOptions.CODEC;
                }
            });
    public static final RegistryObject<ParticleType<ProjectileEffectParticleOptions>> ROCKET_SMOKE =
            PARTICLE_TYPES.register("rocket_smoke", () -> new ParticleType<ProjectileEffectParticleOptions>(
                    false, ProjectileEffectParticleOptions.DESERIALIZER) {
                @Override
                public Codec<ProjectileEffectParticleOptions> m_7652_() {
                    return ProjectileEffectParticleOptions.CODEC;
                }
            });
    public static final RegistryObject<ParticleType<ImpactSparkParticleOptions>> IMPACT_SPARK =
            PARTICLE_TYPES.register("impact_spark", () -> new ParticleType<ImpactSparkParticleOptions>(
                    false, ImpactSparkParticleOptions.DESERIALIZER) {
                @Override
                public Codec<ImpactSparkParticleOptions> m_7652_() {
                    return ImpactSparkParticleOptions.CODEC;
                }
            });
    public static final RegistryObject<SimpleParticleType> EXPLOSION =
            PARTICLE_TYPES.register("explosion", () -> new SimpleParticleType(false) {});

    private ModParticles() {
    }
}
