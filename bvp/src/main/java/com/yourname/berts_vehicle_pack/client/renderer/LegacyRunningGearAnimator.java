package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.yourname.berts_vehicle_pack.client.renderer.RunningGearAnimationSupport.WheelLayout;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Missing-profile compatibility for older tracked resources. */
final class LegacyRunningGearAnimator {
    private static final String[] EXTRA_LEFT_WHEELS = new String[]{"wheelLFront", "wheelLRear"};
    private static final String[] EXTRA_RIGHT_WHEELS = new String[]{"wheelRFront", "wheelRRear"};

    private final int roadWheelCount;
    private final int trackLinkCount;
    private final TrackPathSampler pathSampler;
    private final TrackPathSample currentSample = new TrackPathSample();
    private PolyMeshModel cachedModel;
    private RuntimeLayout cachedLayout;

    LegacyRunningGearAnimator(int roadWheelCount, float trackYCenter, float trackRadius, float trackZRear,
                              float trackZFront, int trackLinkCount) {
        this.roadWheelCount = roadWheelCount;
        this.trackLinkCount = trackLinkCount;
        this.pathSampler = new TrackPathSampler(trackYCenter, trackRadius, trackZRear, trackZFront);
    }

    void apply(GeoVehicleEntity entity, PolyMeshModel model, RunningGearRenderState state) {
        RuntimeLayout layout = layout(model);
        RunningGearAnimationSupport.applyWheelSpin(state, layout.leftWheels, layout.rightWheels);
        hideCrudeTracks(layout);
        int wrapRange = state.getTrackAnimationLength();
        float leftPhase = TrackPathSampler.normalizePhase(state.getLeftTrackPhase(), wrapRange);
        float rightPhase = TrackPathSampler.normalizePhase(state.getRightTrackPhase(), wrapRange);
        if (layout.left.legacyFrames.length > 0 || layout.right.legacyFrames.length > 0) {
            float phaseDistance = this.pathSampler.phaseDistance(this.trackLinkCount);
            animateFrames(layout.left, leftPhase, phaseDistance);
            animateFrames(layout.right, rightPhase, phaseDistance);
        } else if (layout.left.links.length > 0 || layout.right.links.length > 0) {
            animateLinks(layout.left, leftPhase);
            animateLinks(layout.right, rightPhase);
        }
        applyDamageVisibility(entity, layout);
    }

    private RuntimeLayout layout(PolyMeshModel model) {
        if (this.cachedModel != model) {
            this.cachedModel = model;
            this.cachedLayout = new RuntimeLayout(
                    buildSide(model, "L"),
                    buildSide(model, "R"),
                    buildWheels(model, "L", EXTRA_LEFT_WHEELS),
                    buildWheels(model, "R", EXTRA_RIGHT_WHEELS));
        }
        return this.cachedLayout;
    }

    private WheelLayout buildWheels(PolyMeshModel model, String side, String[] extras) {
        List<String> names = new ArrayList<>(this.roadWheelCount + extras.length);
        for (int index = 0; index < this.roadWheelCount; index++) {
            names.add("wheel" + side + index);
        }
        names.addAll(Arrays.asList(extras));
        return RunningGearAnimationSupport.wheels(model, names);
    }

    private SideLayout buildSide(PolyMeshModel model, String side) {
        int linkCount = availableLinkCount(model, side);
        LinkLayout[] links = new LinkLayout[linkCount];
        TrackPathSample base = new TrackPathSample();
        float phaseDistance = this.pathSampler.phaseDistance(linkCount);
        for (int index = 0; index < linkCount; index++) {
            float basePhase = phaseDistance * index;
            this.pathSampler.sample(basePhase, base);
            links[index] = new LinkLayout(
                    model.getBone("trackMov" + side + index),
                    model.getBone("trackRot" + side + index),
                    basePhase,
                    base.y,
                    base.z);
        }
        return new SideLayout(
                links,
                RunningGearAnimationSupport.namedFrames(model, "trackBeltVisual" + side),
                model.getBone("Track" + side),
                model.getBone("brokenTrack" + side),
                model.getBone("crudeTrack" + side));
    }

    private static int availableLinkCount(PolyMeshModel model, String side) {
        int count = 0;
        while (model.getBone("trackMov" + side + count) != null
                && model.getBone("trackRot" + side + count) != null) {
            count++;
        }
        return count;
    }

    private static void hideCrudeTracks(RuntimeLayout layout) {
        if (layout.crudeTracksHidden) {
            return;
        }
        hideCrudeTrack(layout.left);
        hideCrudeTrack(layout.right);
        layout.crudeTracksHidden = true;
    }

    private static void hideCrudeTrack(SideLayout side) {
        RendererBones.hide(side.crudeTrack);
        RendererBones.resetPosition(side.trackParent);
        side.lastPhase = Float.NaN;
        side.lastFrame = -1;
        side.damageVisibilityInitialized = false;
    }

    private void animateLinks(SideLayout side, float phase) {
        if (Float.compare(side.lastPhase, phase) == 0) {
            return;
        }
        for (LinkLayout link : side.links) {
            this.pathSampler.sample(RendererBones.wrap(phase + link.basePhase, 100.0F), this.currentSample);
            RendererBones.setPositionOffset(link.moveBone, 0.0F,
                    this.currentSample.y - link.baseY,
                    this.currentSample.z - link.baseZ);
            RendererBones.setRotation(link.rotationBone,
                    this.currentSample.rotationXDegrees * RendererBones.DEG_TO_RAD, 0.0F, 0.0F);
        }
        side.lastPhase = phase;
    }

    private static void animateFrames(SideLayout side, float phase, float phaseDistance) {
        if (side.legacyFrames.length == 0) {
            return;
        }
        int frameIndex = RunningGearAnimationSupport.frameIndex(
                phase, side.legacyFrames.length, phaseDistance);
        if (side.lastFrame == frameIndex) {
            return;
        }
        for (int index = 0; index < side.legacyFrames.length; index++) {
            RunningGearAnimationSupport.setVisible(side.legacyFrames[index], index == frameIndex);
        }
        side.lastFrame = frameIndex;
    }

    private static void applyDamageVisibility(GeoVehicleEntity entity, RuntimeLayout layout) {
        ArmoredVehicleEntity armored = entity instanceof ArmoredVehicleEntity value ? value : null;
        boolean canShowBroken = armored != null && !armored.isWreck();
        applyDamageVisibility(layout.left, canShowBroken && armored.isLeftTrackBroken());
        applyDamageVisibility(layout.right, canShowBroken && armored.isRightTrackBroken());
    }

    private static void applyDamageVisibility(SideLayout side, boolean broken) {
        if (side.damageVisibilityInitialized && side.lastBroken == broken) {
            return;
        }
        if (side.trackParent != null) {
            RendererBones.setPositionOffset(side.trackParent, 0.0F, 0.0F, 0.0F);
        }
        if (side.brokenTrack != null) {
            RunningGearAnimationSupport.setVisible(side.brokenTrack, broken);
        }
        side.lastBroken = broken;
        side.damageVisibilityInitialized = true;
    }

    private static final class RuntimeLayout {
        final SideLayout left;
        final SideLayout right;
        final WheelLayout leftWheels;
        final WheelLayout rightWheels;
        boolean crudeTracksHidden;

        RuntimeLayout(SideLayout left, SideLayout right, WheelLayout leftWheels, WheelLayout rightWheels) {
            this.left = left;
            this.right = right;
            this.leftWheels = leftWheels;
            this.rightWheels = rightWheels;
        }
    }

    private static final class SideLayout {
        final LinkLayout[] links;
        final BedrockBone[] legacyFrames;
        final BedrockBone trackParent;
        final BedrockBone brokenTrack;
        final BedrockBone crudeTrack;
        float lastPhase = Float.NaN;
        int lastFrame = -1;
        boolean lastBroken;
        boolean damageVisibilityInitialized;

        SideLayout(LinkLayout[] links, BedrockBone[] legacyFrames, BedrockBone trackParent,
                   BedrockBone brokenTrack, BedrockBone crudeTrack) {
            this.links = links;
            this.legacyFrames = legacyFrames;
            this.trackParent = trackParent;
            this.brokenTrack = brokenTrack;
            this.crudeTrack = crudeTrack;
        }
    }

    private static final class LinkLayout {
        final BedrockBone moveBone;
        final BedrockBone rotationBone;
        final float basePhase;
        final float baseY;
        final float baseZ;

        LinkLayout(BedrockBone moveBone, BedrockBone rotationBone, float basePhase, float baseY, float baseZ) {
            this.moveBone = moveBone;
            this.rotationBone = rotationBone;
            this.basePhase = basePhase;
            this.baseY = baseY;
            this.baseZ = baseZ;
        }
    }
}
