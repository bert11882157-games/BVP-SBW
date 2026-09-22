package com.yourname.berts_vehicle_pack.entity.projectile;

import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class BvpS8KoRocketEntity extends MediumRocketEntity {
    private static final ResourceLocation MODEL_LOCATION =
            new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi24v_s8ko_projectile.geo.json");

    public BvpS8KoRocketEntity(EntityType<? extends BvpS8KoRocketEntity> type, Level level) {
        super(type, level);
        setDamage(100.0F);
        setExplosionDamage(0.0F);
        setExplosionRadius(6.0F);
        setLife(60);
        setGravity(0.02F);
    }

    @Override
    public ResourceLocation getModel() {
        return MODEL_LOCATION;
    }
}
