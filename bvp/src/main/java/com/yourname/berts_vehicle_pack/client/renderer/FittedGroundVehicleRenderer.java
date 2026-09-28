package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Native running gear and accepted turret articulation for source-fitted ground vehicles. */
public final class FittedGroundVehicleRenderer extends BaseTrackedVehicleRenderer<GeoVehicleEntity> {
    private PolyMeshModel boundModel;
    private DefaultVehicleResource boundResource;
    private List<BedrockBone> pitchBones = List.of();
    private FittedGroundRigPose activePose;

    public FittedGroundVehicleRenderer(EntityRendererProvider.Context context, ResourceLocation model,
                                        ResourceLocation texture, String name) {
        super(context, model, texture, name);
    }

    @Override
    public synchronized boolean render(VehicleRenderBackendContext context) {
        FittedGroundRigPose previous = this.activePose;
        try (FittedGroundRigPose pose = new FittedGroundRigPose()) {
            this.activePose = pose;
            return super.render(context);
        } finally {
            this.activePose = previous;
        }
    }

    @Override
    protected void applyModelAnimations(GeoVehicleEntity entity, float entityYaw,
                                        PolyMeshModel model, float partialTicks) {
        super.applyModelAnimations(entity, entityYaw, model, partialTicks);
        DefaultVehicleResource resource = VehicleResource.getDefault(VehicleResource.getRegistryId(entity.m_6095_()));
        if (model != this.boundModel || resource != this.boundResource) {
            this.boundModel = model;
            this.boundResource = resource;
            this.pitchBones = List.of();
            try {
                this.pitchBones = FittedGroundRigPose.bind(resource == null ? null : resource.getFittedGroundRig(), model::getBone);
            } catch (IllegalArgumentException invalid) {
                LogUtils.getLogger().warn("Fitted ground rig skipped: {}", invalid.getMessage());
            }
        }
        if (this.activePose != null && !this.pitchBones.isEmpty()) {
            float pitch = VehicleRenderPartSnapshot.capture(entity, entityYaw, partialTicks).getBarrelPitchDegrees();
            this.activePose.apply(this.pitchBones, pitch);
        }
        // launchers whose model carries the reload rig (9P149 Shturm-S): fire / reload animation
        if (LauncherReloadAnimator.applies(model)) {
            LauncherReloadAnimator.apply(entity, model, partialTicks);
        }
    }
}
