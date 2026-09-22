package com.yourname.berts_vehicle_pack.client.renderer;

import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;

/** Presents normalized accepted flight controls; it owns no flight or weapon state. */
final class Mig19ControlSurfaceAnimator {
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private Mig19ControlSurfaceAnimator() {
    }

    /** Positive channels mean trailing-edge-up elevator, right roll, and right rudder. */
    static void apply(PolyMeshModel model, double elevatorUp, double rightRoll, double rudderRight) {
        float elevator = normalized(elevatorUp) * 20.0F * DEG_TO_RAD;
        float aileron = normalized(rightRoll) * 20.0F * DEG_TO_RAD;
        float rudder = normalized(rudderRight) * 25.0F * DEG_TO_RAD;
        // Axis components include the exporter's Z mirror and the mesh loader's X reversal.
        rotate(model.getBone("aileron_left"), aileron, 0.64140920F, -0.04170297F, 0.76606468F);
        rotate(model.getBone("aileron_right"), aileron, -0.64142131F, -0.04125043F, 0.76607905F);
        rotate(model.getBone("elevator_right"), elevator, -0.69100467F, -0.03117314F, 0.72217781F);
        rotate(model.getBone("elevator_left"), -elevator, 0.69100462F, -0.03117314F, 0.72217785F);
        rotate(model.getBone("rudder"), -rudder, -0.00576332F, 0.75384805F, 0.65702352F);
    }

    private static float normalized(double control) {
        return Double.isFinite(control) ? (float) Math.max(-1.0, Math.min(1.0, control)) : 0.0F;
    }

    private static void rotate(BedrockBone bone, float radians, float axisX, float axisY, float axisZ) {
        if (bone == null) {
            return;
        }
        // Each rendered vehicle replaces the cached model pose, including its neutral state.
        bone.rotation.identity().rotateAxis(radians, axisX, axisY, axisZ);
        var q = bone.rotation;
        double sinPitch = 2.0 * (q.w * (double) q.y - q.z * (double) q.x);
        // Keep the Euler view coherent with rotateZYX, including the raked hinge's cross terms.
        bone.rotationInEuler.set(
                (float) Math.atan2(2.0 * (q.w * (double) q.x + q.y * (double) q.z),
                        1.0 - 2.0 * (q.x * (double) q.x + q.y * (double) q.y)),
                (float) Math.asin(Math.max(-1.0, Math.min(1.0, sinPitch))),
                (float) Math.atan2(2.0 * (q.w * (double) q.z + q.x * (double) q.y),
                        1.0 - 2.0 * (q.y * (double) q.y + q.z * (double) q.z)));
    }
}
