package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.entity.Mi28NEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class Mi28NRenderer extends BaseVehicleRenderer<Mi28NEntity> {
    public Mi28NRenderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation(BertsVehiclePack.MODID, "custom_geo/mi28n.geo.json"),
                new ResourceLocation(BertsVehiclePack.MODID, "textures/entity/mi28n.png"),
                "Mi28NRenderer");
    }

    @Override
    protected void applyModelAnimations(Mi28NEntity entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks) {
        super.applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
        RotorVisualController.apply(entity, loadedModel, partialTicks);
    }
}
