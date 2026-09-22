package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Yaw-only parents compose with, and never replace, the existing spin-child rotation. */
final class WheelSteeringPose {
    // GroundDriveCalculator.finishWheels clamps to +/-0.8 and then multiplies by 0.75.
    private static final float FULL_RUDDER_ROTATION = 0.6F;
    private final Wheel[] wheels;

    private WheelSteeringPose(Wheel[] wheels) {
        this.wheels = wheels;
    }

    /** Complete validation precedes any model mutation; an absent rig needs no binding. */
    static WheelSteeringPose bind(PolyMeshModel model, RunningGearProfile profile) {
        if (profile.getWheelSteering().isEmpty()) return null;
        if (profile.getTrackRender() != null) throw new IllegalArgumentException("tracked steering rig");
        Set<BedrockBone> spinBones = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : profile.getLeftWheelBones()) spinBones.add(model.getBone(name));
        for (String name : profile.getRightWheelBones()) spinBones.add(model.getBone(name));
        if (spinBones.contains(null)) throw new IllegalArgumentException("missing spin bone");
        Set<BedrockBone> steerBones = Collections.newSetFromMap(new IdentityHashMap<>());
        Wheel[] wheels = new Wheel[profile.getWheelSteering().size()];
        for (int index = 0; index < wheels.length; index++) {
            var entry = profile.getWheelSteering().get(index);
            BedrockBone steering = model.getBone(entry.getSteeringBone());
            BedrockBone spin = model.getBone(entry.getWheelBone());
            if (steering == null || spin == null || !spinBones.contains(spin)
                    || spinBones.contains(steering) || !steerBones.add(steering)
                    || spin.parent != steering || steering.parent == null
                    || steering.getChildren().size() != 1 || steering.getChildren().get(0) != spin) {
                throw new IllegalArgumentException("steering parent/spin child mismatch");
            }
            // Bedrock makes authored absolute pivots parent-relative. Coincident pivots must
            // therefore produce zero child translation; runtime never moves either axle.
            if (!zero(spin.x) || !zero(spin.y) || !zero(spin.z)
                    || !Float.isFinite(steering.x) || !Float.isFinite(steering.y)
                    || !Float.isFinite(steering.z)) {
                throw new IllegalArgumentException("steering/spin pivots must coincide");
            }
            wheels[index] = new Wheel(steering, entry.getMaxAngleDegrees(), entry.getSign());
        }
        for (Wheel wheel : wheels) {
            int depth = 0;
            for (BedrockBone parent = wheel.bone.parent; parent != null; parent = parent.parent) {
                if (++depth > 64 || spinBones.contains(parent) || steerBones.contains(parent)) {
                    throw new IllegalArgumentException("steering must not inherit another wheel pose");
                }
            }
        }
        return new WheelSteeringPose(wheels);
    }

    private static boolean zero(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 0.001F;
    }

    static float radians(float rudder, float maximumDegrees, int sign) {
        float normalized = Float.isFinite(rudder)
                ? Math.max(-1.0F, Math.min(1.0F, rudder / FULL_RUDDER_ROTATION)) : 0.0F;
        return normalized * maximumDegrees * sign * RendererBones.DEG_TO_RAD;
    }

    void apply(RunningGearRenderState state) {
        for (Wheel wheel : wheels) {
            wheel.bone.rotation.rotationY(radians(state.getRudderRotation(), wheel.maximum, wheel.sign))
                    .mul(wheel.neutral);
            wheel.bone.rotation.getEulerAnglesZYX(wheel.bone.rotationInEuler);
        }
    }

    void restore() {
        for (Wheel wheel : wheels) {
            wheel.bone.rotation.set(wheel.neutral);
            wheel.bone.rotationInEuler.set(wheel.neutralEuler);
        }
    }

    private static final class Wheel {
        final BedrockBone bone;
        final float maximum;
        final int sign;
        final Quaternionf neutral;
        final Vector3f neutralEuler;

        Wheel(BedrockBone bone, float maximum, int sign) {
            this.bone = bone;
            this.maximum = maximum;
            this.sign = sign;
            this.neutral = new Quaternionf(bone.rotation);
            this.neutralEuler = new Vector3f(bone.rotationInEuler);
        }
    }
}
