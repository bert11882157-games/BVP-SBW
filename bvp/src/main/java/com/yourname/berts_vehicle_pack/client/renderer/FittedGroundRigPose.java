package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.resource.vehicle.FittedGroundRigResource;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Validates source pivots and scopes extra pitch rotations to one render submission. */
final class FittedGroundRigPose implements AutoCloseable {
    private final Map<BedrockBone, SavedRotation> savedRotations = new IdentityHashMap<>();

    static List<BedrockBone> bind(FittedGroundRigResource rig, Function<String, BedrockBone> boneLookup) {
        if (rig == null) return List.of();
        require(Integer.valueOf(1).equals(rig.schema) && "RUNTIME_MODEL_PIXELS".equals(rig.frame)
                && rig.pitchBones != null && rig.pitchBones.length <= 16, "schema/frame/bone census");
        Set<String> names = new HashSet<>();
        List<BedrockBone> result = new ArrayList<>();
        BedrockBone turret = boneLookup.apply("turret"), barrel = boneLookup.apply("barell");
        for (var part : rig.pitchBones) {
            require(part != null && part.bone != null && part.parent != null
                    && names.add(part.bone) && !Set.of("hull", "turret", "barell",
                    "passengerWeaponStationYaw", "passengerWeaponStationPitch").contains(part.bone), "bone identity");
            BedrockBone bone = boneLookup.apply(part.bone), parent = boneLookup.apply(part.parent);
            require(bone != null && parent != null && bone.parent == parent
                    && part.pivot != null && part.pivot.length == 3, "bone parent/pivot");
            double x = 0, y = 0, z = 0;
            Set<BedrockBone> ancestors = new HashSet<>();
            boolean turretAncestor = false;
            for (BedrockBone current = bone; current != null; current = current.parent) {
                require(ancestors.add(current) && ancestors.size() <= 32, "cyclic/deep hierarchy");
                x += current.x; y += current.y; z += current.z;
                if (current == turret) turretAncestor = true;
                if (current != bone) require(current != barrel, "double pitch inheritance");
            }
            require(turretAncestor && Double.isFinite(part.pivot[0]) && Double.isFinite(part.pivot[1])
                    && Double.isFinite(part.pivot[2]) && Math.abs(x + part.pivot[0]) <= 0.001
                    && Math.abs(y - part.pivot[1]) <= 0.001 && Math.abs(z - part.pivot[2]) <= 0.001,
                    "source/model pivot mismatch");
            result.add(bone);
        }
        for (BedrockBone bone : result) for (BedrockBone parent = bone.parent; parent != null; parent = parent.parent) {
            require(!result.contains(parent), "nested pitch channels");
        }
        return List.copyOf(result);
    }

    void apply(List<BedrockBone> bones, float pitchDegrees) {
        if (!Float.isFinite(pitchDegrees)) return;
        for (BedrockBone bone : bones) {
            this.savedRotations.computeIfAbsent(bone, SavedRotation::new);
            RendererBones.setRotation(bone, pitchDegrees * RendererBones.DEG_TO_RAD, 0F, 0F);
        }
    }

    @Override
    public void close() {
        for (SavedRotation saved : this.savedRotations.values()) saved.restore();
        this.savedRotations.clear();
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static final class SavedRotation {
        private final BedrockBone bone;
        private final Quaternionf rotation;
        private final Vector3f euler;

        private SavedRotation(BedrockBone bone) {
            this.bone = bone;
            this.rotation = new Quaternionf(bone.rotation);
            this.euler = new Vector3f(bone.rotationInEuler);
        }

        private void restore() {
            this.bone.rotation.set(this.rotation);
            this.bone.rotationInEuler.set(this.euler);
        }
    }
}
