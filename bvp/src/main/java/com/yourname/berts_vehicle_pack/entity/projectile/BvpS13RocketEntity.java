package com.yourname.berts_vehicle_pack.entity.projectile;

import com.atsuishio.superbwarfare.entity.projectile.MediumRocketEntity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public class BvpS13RocketEntity extends MediumRocketEntity {
    private static final ResourceLocation MODEL_LOCATION =
            new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi24v_s13_projectile.geo.json");

    public BvpS13RocketEntity(EntityType<? extends BvpS13RocketEntity> type, Level level) {
        super(type, level);
        setDamage(175.0F);
        setExplosionDamage(115.0F);
        setExplosionRadius(9.0F);
        setLife(70);
        setGravity(0.025F);
    }

    @Override
    public ResourceLocation getModel() {
        return MODEL_LOCATION;
    }
}
