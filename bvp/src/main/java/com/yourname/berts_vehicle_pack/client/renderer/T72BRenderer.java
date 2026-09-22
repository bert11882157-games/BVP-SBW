package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.entity.T72BEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class T72BRenderer extends BaseTankRenderer<T72BEntity> {
    public T72BRenderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation("berts_vehicle_pack", "custom_geo/t72b.geo.json"),
                new ResourceLocation("berts_vehicle_pack", "textures/entity/t72b.png"),
                6, 9.09873F, 8.40798F, -54.11257F, 46.72195F, 55, "T72BRenderer");
    }

}
