package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.resource.vehicle.FittedGroundRigResource;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.google.gson.Gson;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Real source rig fixtures and temporary Bedrock pose checks without a world or GL context. */
public final class FittedGroundRigPoseTest {
    private static final Gson GSON = new Gson();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Fixture[] fixtures = GSON.fromJson(Files.readString(Path.of(args[0])), Fixture[].class);
        check(fixtures.length == 12, "fitted source census");
        int articulated = 0;
        for (Fixture fixture : fixtures) {
            Map<String, BedrockBone> bones = bones(fixture);
            List<BedrockBone> pitch = FittedGroundRigPose.bind(fixture.rig, bones::get);
            check(pitch.size() == (fixture.rig == null ? 0 : fixture.rig.pitchBones.length), fixture.id);
            if (pitch.isEmpty()) continue;
            articulated++;
            verifyRestoration(pitch);
            reject(fixture, rig -> rig.schema = 2, "schema");
            reject(fixture, rig -> rig.frame = "VEHICLE_BLOCKS", "frame");
            reject(fixture, rig -> rig.pitchBones[0].pivot[0] += 0.02, "source pivot drift");
            reject(fixture, rig -> rig.pitchBones[0].pivot[1] = Double.NaN, "nonfinite pivot");
            reject(fixture, rig -> rig.pitchBones[0].parent = "hull", "wrong parent");
            reject(fixture, rig -> rig.pitchBones[0].bone = "barell", "native barrel protected");
            reject(fixture, rig -> rig.pitchBones[0].bone = "absent", "missing bone");
            reject(fixture, rig -> rig.pitchBones = new FittedGroundRigResource.PitchBone[]{
                    rig.pitchBones[0], rig.pitchBones[0]}, "duplicate channel");
            BedrockBone first = pitch.get(0);
            BedrockBone parent = first.parent;
            first.parent = bones.get("barell");
            expectRejected(() -> FittedGroundRigPose.bind(fixture.rig, bones::get), "wrong hierarchy");
            first.parent = parent;
            BedrockBone turret = bones.get("turret");
            turret.parent = first;
            expectRejected(() -> FittedGroundRigPose.bind(fixture.rig, bones::get), "cycle");
        }
        check(articulated == 4, "BTR, Marder, ZBD and QN pitch rigs");
        nestedChannelRejection();
        System.out.println("PASS FittedGroundRigPose: " + checks + " assertions, 12 source fixtures");
    }

    private static void verifyRestoration(List<BedrockBone> bones) {
        BedrockBone first = bones.get(0);
        RendererBones.setRotation(first, 0.21F, -0.34F, 0.47F);
        Quaternionf original = new Quaternionf(first.rotation);
        Vector3f euler = new Vector3f(first.rotationInEuler);
        float x = first.x, y = first.y, z = first.z;
        for (float degrees : new float[]{-12, 0, 18, 75}) {
            try (FittedGroundRigPose pose = new FittedGroundRigPose()) {
                pose.apply(bones, degrees);
                Quaternionf expected = new Quaternionf().rotateX(degrees * RendererBones.DEG_TO_RAD);
                check(first.rotation.equals(expected, 0.000001F), "accepted pitch only");
                pose.apply(bones, degrees);
                check(first.rotation.equals(expected, 0.000001F), "repeat does not accumulate");
                pose.apply(bones, Float.NaN);
                pose.apply(bones, Float.POSITIVE_INFINITY);
                check(first.rotation.equals(expected, 0.000001F), "nonfinite actual omitted");
                try (FittedGroundRigPose nested = new FittedGroundRigPose()) {
                    nested.apply(bones, -37);
                }
                check(first.rotation.equals(expected, 0.000001F), "nested draw restores outer pose");
            }
            check(first.rotation.equals(original), "quaternion restored exactly");
            check(first.rotationInEuler.equals(euler), "Euler state restored exactly");
            check(first.x == x && first.y == y && first.z == z, "pivot unchanged");
        }
        try (FittedGroundRigPose pose = new FittedGroundRigPose()) {
            pose.apply(bones, 23);
            throw new ExpectedDrawFailure();
        } catch (ExpectedDrawFailure expected) {
            check(first.rotation.equals(original) && first.rotationInEuler.equals(euler), "exception cleanup");
        }
        FittedGroundRigPose empty = new FittedGroundRigPose();
        empty.close();
        empty.close();
        check(first.rotation.equals(original), "empty and repeated cleanup");
    }

    private static void reject(Fixture fixture, Consumer<FittedGroundRigResource> mutation, String label) {
        FittedGroundRigResource rig = GSON.fromJson(GSON.toJson(fixture.rig), FittedGroundRigResource.class);
        mutation.accept(rig);
        Map<String, BedrockBone> bones = bones(fixture);
        expectRejected(() -> FittedGroundRigPose.bind(rig, bones::get), label);
        check(bones.values().stream().allMatch(bone -> bone.rotation.equals(new Quaternionf())),
                "rejected binding has no partial pose");
    }

    private static void nestedChannelRejection() {
        Fixture fixture = GSON.fromJson("""
                {"bones":[{"name":"hull","pivot":[0,0,0]},
                  {"name":"turret","parent":"hull","pivot":[0,2,0]},
                  {"name":"pod","parent":"turret","pivot":[2,3,0]},
                  {"name":"child","parent":"pod","pivot":[2,4,0]}],
                 "rig":{"Schema":1,"Frame":"RUNTIME_MODEL_PIXELS","PitchBones":[
                   {"Bone":"pod","Parent":"turret","Pivot":[2,3,0]},
                   {"Bone":"child","Parent":"pod","Pivot":[2,4,0]}]}}
                """, Fixture.class);
        Map<String, BedrockBone> bones = bones(fixture);
        expectRejected(() -> FittedGroundRigPose.bind(fixture.rig, bones::get), "nested pitch inheritance");
    }

    private static Map<String, BedrockBone> bones(Fixture fixture) {
        Map<String, BedrockBone> bones = new LinkedHashMap<>();
        Map<String, double[]> pivots = new LinkedHashMap<>();
        for (SourceBone source : fixture.bones) {
            BedrockBone bone = new BedrockBone();
            double[] parent = source.parent == null ? new double[3] : pivots.get(source.parent);
            check(parent != null, "parent-first source model");
            bone.x = (float) -(source.pivot[0] - parent[0]);
            bone.y = (float) (source.pivot[1] - parent[1]);
            bone.z = (float) (source.pivot[2] - parent[2]);
            bone.parent = bones.get(source.parent);
            bones.put(source.name, bone);
            pivots.put(source.name, source.pivot);
        }
        return bones;
    }

    private static void expectRejected(Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, label);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class ExpectedDrawFailure extends RuntimeException { }
    private static final class Fixture {
        String id;
        SourceBone[] bones;
        FittedGroundRigResource rig;
    }
    private static final class SourceBone {
        String name;
        String parent;
        double[] pivot;
    }
}
