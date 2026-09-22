package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.Ka50Entity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class Ka50Renderer extends BaseVehicleRenderer<Ka50Entity> {
    public Ka50Renderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/ka50.geo.json"),
                new ResourceLocation(BertsVehiclePack.MODID, "textures/entity/ka50.png"),
                "Ka50Renderer");
    }

    @Override
    protected void applyModelAnimations(Ka50Entity entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks) {
        super.applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
        RotorVisualController.apply(entity, loadedModel, partialTicks);
    }
}
