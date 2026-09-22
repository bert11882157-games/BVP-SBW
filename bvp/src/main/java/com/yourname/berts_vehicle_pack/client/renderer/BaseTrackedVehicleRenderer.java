package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class BaseTrackedVehicleRenderer<T extends GeoVehicleEntity> extends BaseVehicleRenderer<T> {
    private final TrackedRunningGearAnimator runningGearAnimator;

    protected BaseTrackedVehicleRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                         ResourceLocation textureLocation,
                                         int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                                         float trackZFront, String debugName) {
        this(context, modelLocation, textureLocation, roadWheelCount, trackYCenter,
                trackRadius, trackZRear, trackZFront, TrackedRunningGearAnimator.DEFAULT_TRACK_LINK_COUNT, debugName);
    }

    /**
     * Profile-driven tracked backend for newly authored vehicles. It deliberately has no
     * legacy geometry fallback: until a typed RunningGear profile is available the model stays
     * intact/static instead of animating against guessed wheel, track, or link dimensions.
     */
    protected BaseTrackedVehicleRenderer(EntityRendererProvider.Context context,
                                         ResourceLocation modelLocation,
                                         ResourceLocation textureLocation,
                                         String debugName) {
        super(context, modelLocation, textureLocation, debugName);
        this.runningGearAnimator = new TrackedRunningGearAnimator();
    }

    protected BaseTrackedVehicleRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                          ResourceLocation textureLocation,
                                          int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                                          float trackZFront, int trackLinkCount, String debugName) {
        super(context, modelLocation, textureLocation, debugName);
        this.runningGearAnimator = new TrackedRunningGearAnimator(roadWheelCount, trackYCenter, trackRadius,
                trackZRear, trackZFront, trackLinkCount);
    }

    @Override
    protected void applyModelAnimations(T entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks) {
        super.applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
        this.runningGearAnimator.apply(entity, loadedModel, partialTicks);
    }

    @Override
    protected void renderModelSpaceCutout(T entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks,
                                          PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        super.renderModelSpaceCutout(entity, entityYaw, loadedModel, partialTicks,
                poseStack, bufferSource, packedLight);
    }

    @Override
    protected void renderDebugOverlays(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                       MultiBufferSource bufferSource, VehiclePoseSnapshot presentationPose) {
        super.renderDebugOverlays(entity, entityYaw, partialTicks, poseStack, bufferSource, presentationPose);
    }
}
