package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.entity.BmptEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class BmptRenderer extends BaseTankRenderer<BmptEntity> {
    public BmptRenderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation("berts_vehicle_pack", "custom_geo/bmpt.geo.json"),
                new ResourceLocation("berts_vehicle_pack", "textures/entity/bmpt.png"),
                6, 8.7156F, 8.02484F, -51.38747F, 44.36905F, 46, "BmptRenderer");
    }

}
