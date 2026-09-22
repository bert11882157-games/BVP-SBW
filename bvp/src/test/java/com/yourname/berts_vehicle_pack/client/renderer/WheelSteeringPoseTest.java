package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfile;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearProfiles;
import com.atsuishio.superbwarfare.client.renderer.vehicle.RunningGearRenderState;
import com.atsuishio.superbwarfare.resource.vehicle.RunningGearResource;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

/** Production profile validation and Bedrock composition fixtures; no world or GL context. */
public final class WheelSteeringPoseTest {
    private static final Gson GSON = new Gson();
    private static int checks;
    private static final String MODEL = """
            {"format_version":"1.12.0","minecraft:geometry":[{
              "description":{"identifier":"geometry.wheels","texture_width":16,"texture_height":16,
                "visible_bounds_width":32,"visible_bounds_height":32,"visible_bounds_offset":[0,0,0]},
              "bones":[{"name":"hull","pivot":[0,2,0]},
                {"name":"steerL","parent":"hull","pivot":[8,4,-16]},
                {"name":"frontL","parent":"steerL","pivot":[8,4,-16]},
                {"name":"steerR","parent":"hull","pivot":[-8,4,-16]},
                {"name":"frontR","parent":"steerR","pivot":[-8,4,-16]},
                {"name":"rearL","parent":"hull","pivot":[8,4,16]},
                {"name":"rearR","parent":"hull","pivot":[-8,4,16]}]}]}
            """;
    private static final String RESOURCE = """
            {"Type":"WHEELED","RoadWheelCount":2,
             "WheelBones":{"Left":["frontL","rearL"],"Right":["frontR","rearR"]},
             "Steering":{"Schema":1,"Wheels":[
               {"SteeringBone":"steerL","WheelBone":"frontL","MaxAngleDegrees":30,"Sign":1},
               {"SteeringBone":"steerR","WheelBone":"frontR","MaxAngleDegrees":30,"Sign":-1}]}}
            """;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        profileValidation();
        spinComposition();
        modelRejection();
        resetAndReplacement();
        trackedAndLegacy();
        System.out.println("PASS WheelSteering production fixtures: " + checks + " assertions");
    }

    private static JsonObject data() { return JsonParser.parseString(RESOURCE).getAsJsonObject(); }

    private static PolyMeshModel model() {
        return new PolyMeshModel(GSON.fromJson(MODEL, BedrockModelPOJO.class),
                JsonParser.parseString(MODEL).getAsJsonObject());
    }

    private static RunningGearProfile profile(JsonObject json) throws Exception {
        RunningGearResource raw = GSON.fromJson(json, RunningGearResource.class);
        Method validate = RunningGearProfiles.class.getDeclaredMethod("validate", RunningGearResource.class);
        validate.setAccessible(true);
        Object cached = validate.invoke(RunningGearProfiles.INSTANCE, raw);
        Method result = cached.getClass().getDeclaredMethod("getProfile");
        result.setAccessible(true);
        return (RunningGearProfile) result.invoke(cached);
    }

    private static JsonObject first(JsonObject json) {
        return json.getAsJsonObject("Steering").getAsJsonArray("Wheels").get(0).getAsJsonObject();
    }

    private static void profileValidation() throws Exception {
        var profile = profile(data());
        check(profile != null && profile.getWheelSteering().size() == 2, "valid optional profile");
        boolean immutable = false;
        try { profile.getWheelSteering().clear(); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "immutable steering entries");
        reject(json -> json.getAsJsonObject("Steering").addProperty("Schema", 2));
        reject(json -> json.getAsJsonObject("Steering").remove("Schema"));
        reject(json -> json.getAsJsonObject("Steering").remove("Wheels"));
        reject(json -> json.getAsJsonObject("Steering").add("Wheels", JsonParser.parseString("[]")));
        reject(json -> first(json).remove("MaxAngleDegrees"));
        reject(json -> first(json).addProperty("MaxAngleDegrees", 0));
        reject(json -> first(json).addProperty("MaxAngleDegrees", 91));
        reject(json -> first(json).addProperty("MaxAngleDegrees", "NaN"));
        reject(json -> first(json).remove("Sign"));
        reject(json -> first(json).addProperty("Sign", 0));
        reject(json -> first(json).addProperty("WheelBone", "missing"));
        reject(json -> first(json).addProperty("SteeringBone", "frontL"));
        reject(json -> first(json).addProperty("WheelBone", "frontR"));
        reject(json -> first(json).addProperty("SteeringBone", "steerR"));
        reject(json -> json.getAsJsonObject("WheelBones").add("Right",
                JsonParser.parseString("[\"frontR\",\"frontL\"]")));
        reject(json -> json.getAsJsonObject("Steering").getAsJsonArray("Wheels")
                .set(0, com.google.gson.JsonNull.INSTANCE));
        reject(json -> {
            var wheels = json.getAsJsonObject("Steering").getAsJsonArray("Wheels");
            while (wheels.size() <= 16) wheels.add(first(json).deepCopy());
        });
        for (float rudder : new float[]{-6, -0.6F, -0.3F, 0, 0.3F, 0.6F, 6, Float.NaN}) {
            float normalized = Float.isFinite(rudder) ? Math.max(-1, Math.min(1, rudder / 0.6F)) : 0;
            close(WheelSteeringPose.radians(rudder, 30, -1),
                    -normalized * Math.toRadians(30), "bounded accepted rudder");
        }
    }

    private static void reject(Consumer<JsonObject> mutation) throws Exception {
        var json = data();
        mutation.accept(json);
        check(profile(json) == null, "malformed steering profile rejected atomically");
    }

    private static void spinComposition() throws Exception {
        var model = model();
        var profile = profile(data());
        var steering = WheelSteeringPose.bind(model, profile);
        var left = RunningGearAnimationSupport.wheels(model, profile.getLeftWheelBones());
        var right = RunningGearAnimationSupport.wheels(model, profile.getRightWheelBones());
        var near = new RunningGearRenderState(1.25F, -2, 0, 0, 0.3F, 1);
        RunningGearAnimationSupport.applyWheelSpin(near, left, right);
        Quaternionf originalSpin = new Quaternionf(model.getBone("frontL").rotation);
        steering.apply(near);
        quaternion(originalSpin, model.getBone("frontL").rotation, "steering preserves spin child");
        quaternion(model.getBone("frontL").rotation, new Quaternionf().rotateX(1.875F), "legacy spin scale");
        quaternion(model.getBone("rearL").rotation, originalSpin, "rear spin unchanged");
        quaternion(model.getBone("steerL").rotation,
                new Quaternionf().rotateY((float) Math.toRadians(15)), "left steering parent");
        quaternion(model.getBone("steerR").rotation,
                new Quaternionf().rotateY((float) Math.toRadians(-15)), "right authored sign");
        Vector3f actual = new Vector3f(0, 1, 0);
        model.getBone("frontL").rotation.transform(actual);
        model.getBone("steerL").rotation.transform(actual);
        Vector3f expected = new Quaternionf().rotateY((float) Math.toRadians(15)).rotateX(1.875F)
                .transform(new Vector3f(0, 1, 0));
        close(actual.distance(expected), 0, "yaw parent times spin child composition");
        close(model.getBone("frontL").x, 0, "coincident loader pivot X");
        close(model.getBone("frontL").y, 0, "coincident loader pivot Y");
        close(model.getBone("frontL").z, 0, "coincident loader pivot Z");
        var far = new RunningGearRenderState(1.25F, -2, 0, 0, 0.3F, 1);
        Quaternionf nearYaw = new Quaternionf(model.getBone("steerL").rotation);
        steering.restore();
        steering.apply(far);
        quaternion(model.getBone("steerL").rotation, nearYaw, "same near/far state same yaw");
        steering.apply(far);
        quaternion(model.getBone("steerL").rotation, nearYaw, "repeat draw does not accumulate");
        steering.apply(new RunningGearRenderState(0, 0, 0, 0, Float.NaN, 1));
        quaternion(model.getBone("steerL").rotation, new Quaternionf(), "invalid rudder neutral");
        steering.restore();
        quaternion(model.getBone("steerL").rotation, new Quaternionf(), "finally restores steering");
        quaternion(model.getBone("frontL").rotation, originalSpin, "finally leaves legacy spin intact");
    }

    private static void modelRejection() throws Exception {
        var profile = profile(data());
        for (int mode = 0; mode < 4; mode++) {
            var model = model();
            if (mode == 0) model.getBoneMap().remove("steerR");
            if (mode == 1) model.getBone("frontR").parent = model.getBone("hull");
            if (mode == 2) model.getBone("frontR").z = 0.1F;
            if (mode == 3) model.getBone("steerR").parent = model.getBone("frontL");
            boolean rejected = false;
            try { WheelSteeringPose.bind(model, profile); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "missing/invalid model graph fails closed");
            quaternion(model.getBone("steerL").rotation, new Quaternionf(), "no partial left steering");
            quaternion(model.getBone("frontL").rotation, new Quaternionf(), "no partial spin mutation");
        }
    }

    private static void resetAndReplacement() throws Exception {
        var model = model();
        var old = WheelSteeringPose.bind(model, profile(data()));
        old.apply(new RunningGearRenderState(0, 0, 0, 0, 0.6F, 1));
        var animator = new ProfileWheeledRunningGearAnimator();
        Field steering = ProfileWheeledRunningGearAnimator.class.getDeclaredField("steering");
        steering.setAccessible(true);
        steering.set(animator, old);
        animator.reset();
        check(steering.get(animator) == null, "reload clears cached binding");
        quaternion(model.getBone("steerL").rotation, new Quaternionf(), "reload restores neutral");
        var changed = data();
        first(changed).addProperty("MaxAngleDegrees", 10);
        var next = WheelSteeringPose.bind(model, profile(changed));
        next.apply(new RunningGearRenderState(0, 0, 0, 0, 0.6F, 1));
        quaternion(model.getBone("steerL").rotation,
                new Quaternionf().rotateY((float) Math.toRadians(10)), "resource replacement uses new limit");
        next.restore();
    }

    private static void trackedAndLegacy() throws Exception {
        var legacy = data();
        legacy.remove("Steering");
        var profile = profile(legacy);
        check(profile != null && profile.getWheelSteering().isEmpty(), "no-rig legacy profile");
        check(WheelSteeringPose.bind(model(), profile) == null, "no-rig has no pose owner");
        check(new RunningGearProfile(List.of("a"), List.of("b"), null).getWheelSteering().isEmpty(),
                "three-argument Java constructor retained");
        var tracked = data();
        tracked.remove("Steering");
        tracked.addProperty("Type", "TRACKED");
        tracked.add("TrackRender", JsonParser.parseString("""
                {"Mode":"LINKS","LinkCount":2,"PhaseDistance":50,
                 "EvaluationLayout":{"YCenter":0,"Radius":1,"ZRear":-1,"ZFront":1},
                 "Sides":[
                   {"Side":"L","Direction":1,"YCenter":0,"Radius":1,"ZRear":-1,"ZFront":1,
                    "YBottom":0,"YTop":1,"TrackBone":"trackL","BrokenBone":"brokenL",
                    "FallbackBone":"fallbackL","LinkMovePrefix":"moveL","LinkRotationPrefix":"rotL"},
                   {"Side":"R","Direction":-1,"YCenter":0,"Radius":1,"ZRear":-1,"ZFront":1,
                    "YBottom":0,"YTop":1,"TrackBone":"trackR","BrokenBone":"brokenR",
                    "FallbackBone":"fallbackR","LinkMovePrefix":"moveR","LinkRotationPrefix":"rotR"}],
                 "Path":{"SourceYMin":0,"SourceYMax":1,"SourceZMin":0,"SourceZMax":1,
                    "MoveY":[[0,0],[100,1]],"MoveZ":[[0,0],[100,1]],"RotationX":[[0,0],[100,360]]}}
                """));
        var validTrack = profile(tracked);
        check(validTrack != null && validTrack.getTrackRender() != null
                && validTrack.getWheelSteering().isEmpty(), "ordinary LINKS profile unchanged");
        tracked.add("Steering", data().get("Steering"));
        check(profile(tracked) == null, "TRACKED steering rejected");
        legacy.addProperty("Type", "NONE");
        check(profile(legacy) == null, "native/no-profile exclusion unchanged");
    }

    private static void quaternion(Quaternionf actual, Quaternionf expected, String reason) {
        close(1 - Math.abs(actual.dot(expected)), 0, reason);
    }

    private static void close(double actual, double expected, String reason) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) < 0.0001, reason);
    }

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }
}
