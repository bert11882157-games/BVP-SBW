package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;

import java.util.ArrayList;
import java.util.List;

final class RunningGearAnimationSupport {
    private static final float WHEEL_SPIN_SCALE = 1.5F;

    private RunningGearAnimationSupport() {
    }

    static WheelLayout wheels(PolyMeshModel model, List<String> names) {
        List<BedrockBone> wheels = new ArrayList<>();
        for (String name : names) {
            BedrockBone wheel = model.getBone(name);
            if (wheel != null) {
                wheels.add(wheel);
            }
        }
        return new WheelLayout(wheels.toArray(new BedrockBone[0]));
    }

    static void applyWheelSpin(RunningGearRenderState state, WheelLayout left, WheelLayout right) {
        applyWheelSpin(left, WHEEL_SPIN_SCALE * state.getLeftWheelRotation());
        applyWheelSpin(right, WHEEL_SPIN_SCALE * state.getRightWheelRotation());
    }

    private static void applyWheelSpin(WheelLayout side, float spin) {
        if (Float.compare(side.lastSpin, spin) == 0) {
            return;
        }
        for (BedrockBone wheel : side.wheels) {
            RendererBones.setRotation(wheel, spin, 0.0F, 0.0F);
        }
        side.lastSpin = spin;
    }

    static BedrockBone[] namedFrames(PolyMeshModel model, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new BedrockBone[0];
        }
        List<BedrockBone> frames = new ArrayList<>();
        for (int index = 0; ; index++) {
            BedrockBone frame = model.getBone(prefix + index);
            if (frame == null) {
                break;
            }
            frames.add(frame);
        }
        return frames.toArray(new BedrockBone[0]);
    }

    static int frameIndex(float phase, int frameCount, float phaseDistance) {
        if (frameCount <= 1) {
            return 0;
        }
        float range = phaseDistance > 0.0F ? phaseDistance : 100.0F;
        float wrapped = RendererBones.wrap(phase, range);
        return Math.min(frameCount - 1, (int) (wrapped * frameCount / range));
    }

    static void setVisible(BedrockBone bone, boolean visible) {
        if (visible) {
            RendererBones.resetPosition(bone);
        } else {
            RendererBones.hide(bone);
        }
    }

    static final class WheelLayout {
        final BedrockBone[] wheels;
        float lastSpin = Float.NaN;

        WheelLayout(BedrockBone[] wheels) {
            this.wheels = wheels;
        }
    }
}
