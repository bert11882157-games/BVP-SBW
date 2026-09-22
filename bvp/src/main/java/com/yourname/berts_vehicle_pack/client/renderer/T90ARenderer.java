package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.entity.T90AEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class T90ARenderer extends BaseTankRenderer<T90AEntity> {
    public T90ARenderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation("berts_vehicle_pack", "custom_geo/t90a.geo.json"),
                new ResourceLocation("berts_vehicle_pack", "textures/entity/t90a.png"),
                6, 8.7156F, 8.02484F, -51.38747F, 44.36905F, 46, "T90ARenderer");
    }

}
