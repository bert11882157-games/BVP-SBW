package com.yourname.berts_vehicle_pack.client.renderer;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;

import java.util.IdentityHashMap;
import java.util.Map;

final class RendererBones {
    static final float DEG_TO_RAD = 0.017453292F;

    private static final Map<BedrockBone, float[]> BASE_BONE_POSITIONS = new IdentityHashMap<>();

    private RendererBones() {
    }

    static void resetPosition(BedrockBone bone) {
        setPositionOffset(bone, 0.0F, 0.0F, 0.0F);
    }

    static void hide(BedrockBone bone) {
        if (bone != null) {
            setPositionOffset(bone, 0.0F, 0.0F, 0.0F, false);
            setRotation(bone, 0.0F, 0.0F, 0.0F);
        }
    }

    static void show(BedrockBone bone) {
        if (bone != null) {
            bone.visible = true;
        }
    }

    static void setPositionOffset(BedrockBone bone, float x, float y, float z) {
        setPositionOffset(bone, x, y, z, true);
    }

    /** Model guns face local -Z. Bone translation precedes rotation, so rotate the +Z kick once. */
    static void setBackwardRecoil(BedrockBone bone, float distance) {
        if (bone == null) return;
        float amount = Float.isFinite(distance) ? Math.max(0.0F, distance) : 0.0F;
        float x = bone.rotation.x();
        float y = bone.rotation.y();
        float z = bone.rotation.z();
        float w = bone.rotation.w();
        setPositionOffset(bone,
                amount * 2.0F * (x * z + w * y),
                amount * 2.0F * (y * z - w * x),
                amount * (1.0F - 2.0F * (x * x + y * y)));
    }

    private static void setPositionOffset(BedrockBone bone, float x, float y, float z, boolean visible) {
        if (bone == null) {
            return;
        }
        float[] basePosition = BASE_BONE_POSITIONS.computeIfAbsent(bone, key -> new float[]{key.x, key.y, key.z});
        bone.x = basePosition[0] + x;
        bone.y = basePosition[1] + y;
        bone.z = basePosition[2] + z;
        bone.visible = visible;
    }

    static void setRotation(BedrockBone bone, float xRad, float yRad, float zRad) {
        bone.rotation.identity().rotateZYX(zRad, yRad, xRad);
        bone.rotationInEuler.set(xRad, yRad, zRad);
    }

    static void setLongitudinalScale(BedrockBone bone, float scale) {
        if (bone != null) {
            bone.zScale = scale;
        }
    }

    static float approach(float value, float target, float maxStep) {
        if (maxStep <= 0.0F) {
            return value;
        }
        if (value < target) {
            return Math.min(target, value + maxStep);
        }
        return Math.max(target, value - maxStep);
    }

    static float wrap(float value, float range) {
        if (range <= 0.0F) {
            return value;
        }
        return (value % range + range) % range;
    }
}
