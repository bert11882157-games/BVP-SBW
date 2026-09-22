package com.yourname.berts_vehicle_pack.client.renderer;

import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Exercises the production bone helper and actual Bedrock transform, without a world or renderer. */
public final class RendererBonesRecoilTest {
    private static int checks;

    public static void main(String[] args) {
        for (float heading : new float[]{-180, -135, -90, -35, 0, 35, 90, 135, 180}) {
            for (float pitch : new float[]{-45, -10, 0, 20, 60}) {
                for (float amount : new float[]{0.45F, 1.15F, 0.32F, 2.4F, 3.25F}) {
                    Quaternionf parent = new Quaternionf().rotateY(heading * RendererBones.DEG_TO_RAD)
                            .rotateX(0.19F).rotateZ(-0.13F);
                    BedrockBone gun = bone();
                    RendererBones.setRotation(gun, pitch * RendererBones.DEG_TO_RAD, 0.03F, -0.02F);
                    Matrix4f neutral = transform(parent, gun);
                    Vector3f before = neutral.transformPosition(new Vector3f(0.1F, 0.2F, -2));
                    Vector3f forward = neutral.transformDirection(new Vector3f(0, 0, -1)).normalize();
                    RendererBones.setBackwardRecoil(gun, amount);
                    Vector3f moved = transform(parent, gun).transformPosition(new Vector3f(0.1F, 0.2F, -2)).sub(before);
                    near(moved.length(), amount / 16.0F);
                    near(moved.dot(forward), -amount / 16.0F);
                    check(new Vector3f(moved).cross(forward).length() < 2.0E-6F, "kick remains axial");
                    Vector3f previous = new Vector3f(gun.x, gun.y, gun.z);
                    RendererBones.setBackwardRecoil(gun, amount);
                    near(previous.distance(gun.x, gun.y, gun.z), 0);
                    float oldDistance = Float.POSITIVE_INFINITY;
                    for (float fraction : new float[]{1, 0.75F, 0.5F, 0.25F, 0}) {
                        RendererBones.setBackwardRecoil(gun, amount * fraction);
                        float distance = transform(parent, gun).transformPosition(new Vector3f(0.1F, 0.2F, -2)).distance(before);
                        check(distance <= oldDistance + 2.0E-6F, "recovery approaches neutral");
                        near(distance, amount * fraction / 16.0F);
                        oldDistance = distance;
                    }
                    near(gun.x, 2);
                    near(gun.y, 3);
                    near(gun.z, 4);
                    for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1}) {
                        RendererBones.setBackwardRecoil(gun, invalid);
                        near(gun.x, 2);
                        near(gun.y, 3);
                        near(gun.z, 4);
                    }
                    // Dedicated child recoil (BTR) and pitched-bone recoil (BMP/HMG) agree.
                    BedrockBone child = new BedrockBone();
                    RendererBones.setBackwardRecoil(child, amount);
                    PoseStack childPose = new PoseStack();
                    childPose.mulPose(parent);
                    gun.translateAndRotateAndScale(childPose);
                    child.translateAndRotateAndScale(childPose);
                    Vector3f childPoint = childPose.last().pose().transformPosition(new Vector3f(0.1F, 0.2F, -2));
                    RendererBones.setBackwardRecoil(gun, amount);
                    Vector3f directPoint = transform(parent, gun).transformPosition(new Vector3f(0.1F, 0.2F, -2));
                    near(childPoint.distance(directPoint), 0);
                    RendererBones.resetPosition(gun);
                }
            }
        }
        RendererBones.setBackwardRecoil(null, 1);
        System.out.println("RendererBones recoil: " + checks + " production-transform checks passed");
    }

    private static BedrockBone bone() {
        BedrockBone value = new BedrockBone();
        value.x = 2;
        value.y = 3;
        value.z = 4;
        RendererBones.resetPosition(value);
        return value;
    }

    private static Matrix4f transform(Quaternionf parent, BedrockBone bone) {
        PoseStack pose = new PoseStack();
        pose.mulPose(parent);
        bone.translateAndRotateAndScale(pose);
        return new Matrix4f(pose.last().pose());
    }

    private static void near(float actual, float expected) {
        check(Math.abs(actual - expected) < 2.0E-6F, actual + " != " + expected);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
