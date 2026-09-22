package com.yourname.berts_vehicle_pack.client.renderer;

import com.yourname.berts_vehicle_pack.entity.Mi24VEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class Mi24VRenderer extends BaseVehicleRenderer<Mi24VEntity> {
    public Mi24VRenderer(EntityRendererProvider.Context context) {
        super(context,
                new ResourceLocation("berts_vehicle_pack", "custom_geo/mi24v.geo.json"),
                new ResourceLocation("berts_vehicle_pack", "textures/entity/mi24v.png"),
                "Mi24VRenderer");
    }

    @Override
    protected void applyModelAnimations(Mi24VEntity entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks) {
        super.applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
        RotorVisualController.apply(entity, loadedModel, partialTicks);
        YakbVisualController.apply(entity, loadedModel, partialTicks);
    }
}
