package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearSide;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearTrackEvaluator;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearVisualSelector;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackRenderProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackSideProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.TrackVisualState;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.client.renderer.RunningGearAnimationSupport.WheelLayout;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.entity.BvpFarVehicleVisuals;

final class ProfileRunningGearAnimator {
    private PolyMeshModel cachedModel;
    private RunningGearProfile cachedProfile;
    private RuntimeLayout cachedLayout;
    private final java.util.Map<GeoVehicleEntity, Long> diagnosticTicks = new java.util.WeakHashMap<>();

    void apply(GeoVehicleEntity entity, PolyMeshModel model, RunningGearRenderState state,
               RunningGearProfile runningGearProfile, TrackRenderProfile trackProfile) {
        RuntimeLayout layout = layout(model, runningGearProfile, trackProfile);
        RunningGearAnimationSupport.applyWheelSpin(state, layout.leftWheels, layout.rightWheels);

        int wrapRange = state.getTrackAnimationLength();
        float leftPhase = RunningGearTrackEvaluator.normalizePhase(state.getLeftTrackPhase(), wrapRange)
                * layout.left.profile.getDirection();
        float rightPhase = RunningGearTrackEvaluator.normalizePhase(state.getRightTrackPhase(), wrapRange)
                * layout.right.profile.getDirection();
        animate(layout.left, trackProfile, leftPhase);
        animate(layout.right, trackProfile, rightPhase);
        applyVisibility(entity, layout);
        recordVisibility(entity, layout);
    }

    private void recordVisibility(GeoVehicleEntity entity, RuntimeLayout layout) {
        if (!EliteDiagnostics.isEnabled(entity.m_9236_())) return;
        long tick = entity.m_9236_().m_46467_();
        Long previous = diagnosticTicks.get(entity);
        if (previous != null && tick >= previous && tick - previous < 20) return;
        diagnosticTicks.put(entity, tick);
        for (SideLayout side : new SideLayout[]{layout.left, layout.right}) {
            EliteDiagnostics.record(entity, "running_gear", "TRACK_VISIBILITY",
                    "side", side.profile.getSide(), "state", side.lastVisualState,
                    "intact_visible", side.trackParent != null && side.trackParent.visible,
                    "broken_visible", side.brokenTrack != null && side.brokenTrack.visible,
                    "fallback_visible", side.fallbackTrack != null && side.fallbackTrack.visible,
                    "links", side.links.length);
        }
    }

    private RuntimeLayout layout(PolyMeshModel model, RunningGearProfile profile, TrackRenderProfile trackProfile) {
        if (this.cachedModel != model || this.cachedProfile != profile) {
            this.cachedModel = model;
            this.cachedProfile = profile;
            this.cachedLayout = new RuntimeLayout(
                    buildSide(model, trackProfile.side(RunningGearSide.LEFT), trackProfile.getLinkCount()),
                    buildSide(model, trackProfile.side(RunningGearSide.RIGHT), trackProfile.getLinkCount()),
                    RunningGearAnimationSupport.wheels(model, profile.getLeftWheelBones()),
                    RunningGearAnimationSupport.wheels(model, profile.getRightWheelBones()));
        }
        return this.cachedLayout;
    }

    private static SideLayout buildSide(PolyMeshModel model, TrackSideProfile profile, int linkCount) {
        LinkBonePair[] links = new LinkBonePair[linkCount];
        boolean linksReady = true;
        for (int index = 0; index < linkCount; index++) {
            BedrockBone move = model.getBone(profile.getLinkMovePrefix() + index);
            BedrockBone rotation = model.getBone(profile.getLinkRotationPrefix() + index);
            if (move == null || rotation == null) {
                linksReady = false;
                links = new LinkBonePair[0];
                break;
            }
            links[index] = new LinkBonePair(move, rotation, index);
        }
        BedrockBone[] legacyFrames = RunningGearAnimationSupport.namedFrames(
                model, profile.getLegacyFramePrefix());
        if (linksReady) {
            for (BedrockBone legacyFrame : legacyFrames) {
                RendererBones.hide(legacyFrame);
            }
        } else if (legacyFrames.length > 0) {
            for (int index = 0; index < linkCount; index++) {
                RendererBones.hide(model.getBone(profile.getLinkMovePrefix() + index));
                RendererBones.hide(model.getBone(profile.getLinkRotationPrefix() + index));
            }
        }
        BedrockBone trackParent = model.getBone(profile.getTrackBone());
        return new SideLayout(
                profile,
                links,
                legacyFrames,
                trackParent,
                model.getBone(profile.getBrokenBone()),
                model.getBone(profile.getFallbackBone()),
                trackParent != null && (linksReady || legacyFrames.length > 0));
    }

    private static void animate(SideLayout side, TrackRenderProfile profile, float phase) {
        if (Float.compare(side.lastPhase, phase) == 0) {
            return;
        }
        if (side.links.length > 0) {
            long performanceStarted = ClientRenderPerformanceDiagnostics.startTimer();
            for (LinkBonePair link : side.links) {
                RunningGearTrackEvaluator.linkPoseInto(
                        profile, side.profile.getSide(), link.index, phase, side.currentPose, 0);
                RendererBones.setPositionOffset(link.moveBone, 0.0F, side.currentPose[0], side.currentPose[1]);
                RendererBones.setRotation(link.rotationBone,
                        side.currentPose[2] * RendererBones.DEG_TO_RAD, 0.0F, 0.0F);
                RendererBones.setLongitudinalScale(link.rotationBone, side.currentPose[3]);
            }
            ClientRenderPerformanceDiagnostics.recordLinksTransformRebuild(
                    performanceStarted, side.links.length);
        } else if (side.legacyFrames.length > 0) {
            int frameIndex = RunningGearAnimationSupport.frameIndex(
                    phase, side.legacyFrames.length, profile.getPhaseDistance());
            for (int index = 0; index < side.legacyFrames.length; index++) {
                RunningGearAnimationSupport.setVisible(side.legacyFrames[index], index == frameIndex);
            }
        }
        side.lastPhase = phase;
    }

    private static void applyVisibility(GeoVehicleEntity entity, RuntimeLayout layout) {
        ArmoredVehicleEntity armored = entity instanceof ArmoredVehicleEntity value ? value : null;
        boolean canShowBroken = armored != null && !armored.isWreck();
        applyVisibility(layout.left, canShowBroken && BvpFarVehicleVisuals.trackBroken(armored, true));
        applyVisibility(layout.right, canShowBroken && BvpFarVehicleVisuals.trackBroken(armored, false));
    }

    private static void applyVisibility(SideLayout side, boolean brokenRequested) {
        TrackVisualState state = RunningGearVisualSelector.select(
                brokenRequested,
                side.intactReady,
                side.brokenTrack != null,
                side.fallbackTrack != null);
        if (side.visualStateInitialized && side.lastVisualState == state) {
            return;
        }
        RunningGearAnimationSupport.setVisible(side.trackParent, state == TrackVisualState.INTACT);
        RunningGearAnimationSupport.setVisible(side.brokenTrack, state == TrackVisualState.BROKEN);
        RunningGearAnimationSupport.setVisible(side.fallbackTrack, state == TrackVisualState.FALLBACK);
        side.lastVisualState = state;
        side.visualStateInitialized = true;
    }

    private static final class RuntimeLayout {
        final SideLayout left;
        final SideLayout right;
        final WheelLayout leftWheels;
        final WheelLayout rightWheels;

        RuntimeLayout(SideLayout left, SideLayout right, WheelLayout leftWheels, WheelLayout rightWheels) {
            this.left = left;
            this.right = right;
            this.leftWheels = leftWheels;
            this.rightWheels = rightWheels;
        }
    }

    private static final class SideLayout {
        final TrackSideProfile profile;
        final LinkBonePair[] links;
        final BedrockBone[] legacyFrames;
        final BedrockBone trackParent;
        final BedrockBone brokenTrack;
        final BedrockBone fallbackTrack;
        final boolean intactReady;
        final float[] currentPose = new float[4];
        float lastPhase = Float.NaN;
        TrackVisualState lastVisualState;
        boolean visualStateInitialized;

        SideLayout(TrackSideProfile profile, LinkBonePair[] links, BedrockBone[] legacyFrames,
                   BedrockBone trackParent, BedrockBone brokenTrack, BedrockBone fallbackTrack,
                   boolean intactReady) {
            this.profile = profile;
            this.links = links;
            this.legacyFrames = legacyFrames;
            this.trackParent = trackParent;
            this.brokenTrack = brokenTrack;
            this.fallbackTrack = fallbackTrack;
            this.intactReady = intactReady;
        }
    }

    private static final class LinkBonePair {
        final BedrockBone moveBone;
        final BedrockBone rotationBone;
        final int index;

        LinkBonePair(BedrockBone moveBone, BedrockBone rotationBone, int index) {
            this.moveBone = moveBone;
            this.rotationBone = rotationBone;
            this.index = index;
        }
    }
}
