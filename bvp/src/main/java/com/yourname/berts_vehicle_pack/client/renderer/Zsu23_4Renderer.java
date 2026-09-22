package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.entity.Zsu23_4Entity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class Zsu23_4Renderer extends BaseTankRenderer<Zsu23_4Entity> {
    public Zsu23_4Renderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation("berts_vehicle_pack", "custom_geo/zsu23_4.geo.json"),
                new ResourceLocation("berts_vehicle_pack", "textures/entity/zsu23_4.png"),
                6, 8.00334F, 7.96955F, -43.12706F, 53.53320F, 55, "Zsu23_4Renderer");
    }

}
