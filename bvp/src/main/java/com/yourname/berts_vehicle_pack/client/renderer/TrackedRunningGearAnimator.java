package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfiles;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackRenderProfile;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;

/** Routes authored track profiles and missing-profile compatibility through isolated implementations. */
final class TrackedRunningGearAnimator {
    static final int DEFAULT_TRACK_LINK_COUNT = 38;

    private final ProfileRunningGearAnimator profileAnimator = new ProfileRunningGearAnimator();
    private final LegacyRunningGearAnimator legacyAnimator;

    TrackedRunningGearAnimator(int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                               float trackZFront, int trackLinkCount) {
        this.legacyAnimator = new LegacyRunningGearAnimator(
                roadWheelCount, trackYCenter, trackRadius, trackZRear, trackZFront, trackLinkCount);
    }

    /** Profile-only mode: missing profiles leave running gear static, with no legacy fallback. */
    TrackedRunningGearAnimator() {
        this.legacyAnimator = null;
    }

    void apply(GeoVehicleEntity entity, PolyMeshModel loadedModel, float partialTicks) {
        RunningGearProfile runningGearProfile = RunningGearProfiles.resolve(entity);
        TrackRenderProfile trackProfile = runningGearProfile == null ? null : runningGearProfile.getTrackRender();
        // BaseVehicleRenderer already applies typed WHEELED wheel bones. Do not
        // run the legacy tracked fallback or spin those wheels a second time.
        if (runningGearProfile != null && trackProfile == null) {
            return;
        }
        RunningGearRenderState runningGear = RunningGearRenderState.capture(entity, partialTicks);
        if (trackProfile == null) {
            if (this.legacyAnimator != null) {
                this.legacyAnimator.apply(entity, loadedModel, runningGear);
            }
            return;
        }
        // All active tracked profiles are ordinary LINKS. The validated profile
        // API rejects any retired render mode before this shared path is reached.
        this.profileAnimator.apply(entity, loadedModel, runningGear, runningGearProfile, trackProfile);
    }
}
