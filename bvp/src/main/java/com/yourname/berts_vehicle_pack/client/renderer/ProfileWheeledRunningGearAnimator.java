package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfiles;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.atsuishio.superbwarfare.resource.vehicle.RunningGearResource;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/** Applies typed wheel bones for standard vehicles without touching tracked/native paths. */
final class ProfileWheeledRunningGearAnimator {
    private PolyMeshModel cachedModel;
    private EntityType<?> cachedEntityType;
    private RunningGearResource cachedResource;
    private RunningGearAnimationSupport.WheelLayout left;
    private RunningGearAnimationSupport.WheelLayout right;
    private WheelSteeringPose steering;
    private int warningsRemaining = 4;

    void apply(GeoVehicleEntity entity, PolyMeshModel model, float partialTicks) {
        EntityType<?> entityType = entity.m_6095_();
        RunningGearResource resource = RunningGearProfiles.resourceIdentity(entity);
        if (model != this.cachedModel || entityType != this.cachedEntityType || resource != this.cachedResource) {
            rebuild(model, entityType, entity, resource);
        }
        if (this.left == null || this.right == null) {
            return;
        }
        RunningGearRenderState state = RunningGearRenderState.capture(entity, partialTicks);
        RunningGearAnimationSupport.applyWheelSpin(state, this.left, this.right);
        if (this.steering != null) this.steering.apply(state);
    }

    void restoreSteering() {
        if (this.steering != null) this.steering.restore();
    }

    void reset() {
        restoreSteering();
        this.cachedModel = null;
        this.cachedEntityType = null;
        this.cachedResource = null;
        this.left = null;
        this.right = null;
        this.steering = null;
        this.warningsRemaining = 4;
    }

    private void rebuild(
            PolyMeshModel model,
            EntityType<?> entityType,
            GeoVehicleEntity entity,
            RunningGearResource resource) {
        restoreSteering();
        this.steering = null;
        this.cachedModel = model;
        this.cachedEntityType = entityType;
        this.cachedResource = resource;
        RunningGearProfile profile = RunningGearProfiles.resolve(entity);
        this.left = null;
        this.right = null;
        if (profile == null || profile.getTrackRender() != null) {
            return;
        }
        this.left = completeWheels(model, profile.getLeftWheelBones());
        this.right = completeWheels(model, profile.getRightWheelBones());
        if (this.left == null || this.right == null) {
            this.left = null;
            this.right = null;
            return;
        }
        try {
            this.steering = WheelSteeringPose.bind(model, profile);
        } catch (IllegalArgumentException invalid) {
            if (this.warningsRemaining > 0) {
                this.warningsRemaining--;
                com.mojang.logging.LogUtils.getLogger().warn("Wheel steering rig skipped for {}: {}",
                        entityType, invalid.getMessage());
            }
        }
    }

    private static RunningGearAnimationSupport.WheelLayout completeWheels(
            PolyMeshModel model, List<String> names) {
        RunningGearAnimationSupport.WheelLayout layout =
                RunningGearAnimationSupport.wheels(model, names);
        return layout.wheels.length == names.size() ? layout : null;
    }
}
