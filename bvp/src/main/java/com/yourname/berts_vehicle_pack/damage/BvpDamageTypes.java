package com.yourname.berts_vehicle_pack.damage;

import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class BvpDamageTypes {
    private static final ResourceKey<DamageType> TURRET_CRUSH =
            key("turret_crush");
    private static final ResourceKey<DamageType> TURRET_CRUSH_2 =
            key("turret_crush_2");
    private static final ResourceKey<DamageType> TURRET_CRUSH_3 =
            key("turret_crush_3");
    private static final ResourceKey<DamageType> SUPER_AMMO_RACK =
            key("super_ammo_rack");

    private static final List<ResourceKey<DamageType>> TURRET_CRUSH_MESSAGES =
            List.of(TURRET_CRUSH, TURRET_CRUSH_2, TURRET_CRUSH_3);

    private BvpDamageTypes() {
    }

    public static DamageSource randomTurretCrush(Level level, Entity turretWreck) {
        int index = ThreadLocalRandom.current().nextInt(TURRET_CRUSH_MESSAGES.size());
        return source(level, TURRET_CRUSH_MESSAGES.get(index), turretWreck);
    }

    public static DamageSource superAmmoRack(Level level, Entity vehicle, Entity attacker) {
        Holder<DamageType> holder = level.m_9598_().m_175515_(Registries.f_268580_).m_246971_(SUPER_AMMO_RACK);
        return new DamageSource(holder, vehicle, attacker);
    }

    private static DamageSource source(Level level, ResourceKey<DamageType> key, Entity directEntity) {
        Holder<DamageType> holder = level.m_9598_().m_175515_(Registries.f_268580_).m_246971_(key);
        return directEntity == null ? new DamageSource(holder) : new DamageSource(holder, directEntity);
    }

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.m_135785_(Registries.f_268580_, new ResourceLocation(BertsVehiclePack.MODID, name));
    }
}
