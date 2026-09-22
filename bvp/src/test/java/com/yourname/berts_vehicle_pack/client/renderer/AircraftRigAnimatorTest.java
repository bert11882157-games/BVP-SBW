package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.AircraftRigResource;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** No-world fixtures exercise the production parser, mesh-loader basis, and rig pose owner. */
public final class AircraftRigAnimatorTest {
    private static int checks;
    private static final Gson GSON = new Gson();
    private static final String MODEL = """
            {"format_version":"1.12.0","minecraft:geometry":[{
              "description":{"identifier":"geometry.rig","texture_width":16,"texture_height":16,
                "visible_bounds_width":32,"visible_bounds_height":32,"visible_bounds_offset":[0,0,0]},
              "bones":[{"name":"hull","pivot":[1,2,3]},
                {"name":"surface","parent":"hull","pivot":[7,8,9]},
                {"name":"propA","parent":"hull","pivot":[-4,5,6]},
                {"name":"propB","parent":"hull","pivot":[4,5,6]},
                {"name":"tail","parent":"hull","pivot":[0,9,-16]}]}]}
            """;
    private static final String RESOURCE = """
            {"AircraftRig":{"Schema":1,"Frame":"GENERATED_MODEL_PIXELS",
              "Surfaces":[{"Bone":"surface","Parent":"hull","Pivot":[7,8,9],
                "Axis":[1,0,0],"Channel":"elevatorUp","AngleSign":1,
                "MaxDeflectionDegrees":30}],
              "Rotors":[{"Bone":"propA","Parent":"hull","Pivot":[-4,5,6],
                "Axis":[0,0,1],"SpeedChannel":"engineSpool","Direction":1,
                "DegreesPerTickAtFullSpeed":30},
                {"Bone":"propB","Parent":"hull","Pivot":[4,5,6],
                "Axis":[0,0,1],"SpeedChannel":"engineSpool","Direction":-1,
                "DegreesPerTickAtFullSpeed":30},
                {"Bone":"tail","Parent":"hull","Pivot":[0,9,-16],
                "Axis":[1,0,0],"SpeedChannel":"engineSpool","Direction":1,
                "DegreesPerTickAtFullSpeed":120}]}}
            """;

    public static void main(String[] args) throws java.io.IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        modelBasisAndNeutral();
        channelsAndReflection();
        rotorsAndClock();
        windDrivenGenerator();
        invalidAtomicity();
        resetAndResourceReplacement();
        mixedControlsAndNestedSweep();
        sweepScheduleAndFrameIndependence();
        invalidV2Atomicity();
        defensiveStationGeometry();
        defensiveStationRejectionAndReset();
        gearVisibilityAndRestore();
        gearDoorHingeAndRestore();
        invalidGearAtomicity();
        landingFlapStateAndRestore();
        invalidLandingFlapAtomicity();
        if (args.length == 1) actualLandingFlapMesh(args[0]);
        System.out.println("PASS AircraftRig production fixtures: " + checks + " assertions");
    }

    private static PolyMeshModel model() {
        return new PolyMeshModel(GSON.fromJson(MODEL, BedrockModelPOJO.class),
                JsonParser.parseString(MODEL).getAsJsonObject());
    }

    private static void windDrivenGenerator() {
        var data = GSON.fromJson(RESOURCE, DefaultVehicleResource.class).getAircraftRig();
        data.rotors[0].speedChannel = "presentedSpeed";
        var binding = AircraftRigAnimator.bind(data, model()::getBone);
        var phase = new AircraftRigAnimator.Phase(binding);
        phase.advance(0, 1, 0);
        phase.advance(1, 1, 0);
        close(phase.degrees[0], 0, "parked generator cannot follow rocket throttle");
        close(phase.degrees[1], -30, "existing engine propeller retains its channel");
        phase.reset();
        phase.advance(0, 0, .5);
        phase.advance(1, 0, .5);
        close(phase.degrees[0], 15, "generator turns during engine-off glide");
        close(phase.degrees[1], 0, "engine propeller cannot follow glide speed");
        phase.advance(30, 0, .5);
        close(phase.degrees[0], 0, "long absent interval does not cause catch-up spin");
    }

    private static AircraftRigResource flapResource() {
        return GSON.fromJson("""
                {"AircraftRig":{"Schema":2,"Frame":"GENERATED_MODEL_PIXELS",
                  "Surfaces":[],"Rotors":[],"Sweeps":[],
                  "Gear":[{"Bone":"gear_nose","Parent":"hull"}],"Flaps":[
                    {"Bone":"surface","Parent":"hull","Pivot":[7,8,9],"Axis":[1,0,0],
                     "Input":"PRESENTED_LANDING_GEAR_EXTENSION","AngleSign":-1,
                     "MaxDeflectionDegrees":25}]}}
                """, DefaultVehicleResource.class).getAircraftRig();
    }

    private static void landingFlapStateAndRestore() {
        var model = gearModel();
        var binding = AircraftRigAnimator.bind(flapResource(), model::getBone);
        var bone = model.getBone("surface");
        Vector3f pivot = new Vector3f(bone.x, bone.y, bone.z);
        for (double gear : new double[]{0, .25, .5, .75, 1, .5, 0, 1}) {
            for (int copy = 0; copy < 2; copy++) {
                binding.applyFlaps(true, gear);
                quaternion(bone.rotation, new Quaternionf().rotateX(
                        (float) Math.toRadians(-25 * (1 - gear))), "gear-derived flap angle");
                close(new Vector3f(bone.x, bone.y, bone.z).distance(pivot), 0, "source hinge preserved");
                binding.restore();
                quaternion(bone.rotation, new Quaternionf(), "flap shared-model restoration");
            }
        }
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, -.01, 1.01}) {
            binding.applyFlaps(true, 0);
            binding.applyFlaps(true, invalid);
            quaternion(bone.rotation, new Quaternionf(), "invalid gear fraction fails neutral");
        }
        binding.applyFlaps(true, 0);
        binding.applyFlaps(false, 0);
        quaternion(bone.rotation, new Quaternionf(), "no capability cannot invent landing state");
        binding.restore();
    }

    private static void invalidLandingFlapAtomicity() {
        for (int failure = 0; failure < 9; failure++) {
            var model = gearModel();
            var data = flapResource();
            switch (failure) {
                case 0 -> data.flaps[0].input = "LOCAL_KEY";
                case 1 -> data.flaps[0].angleSign = 0;
                case 2 -> data.flaps[0].maxDeflectionDegrees = Double.NaN;
                case 3 -> data.flaps[0].parent = "propA";
                case 4 -> data.flaps[0].bone = "missing";
                case 5 -> data.flaps = new AircraftRigResource.Flap[17];
                case 6 -> data.flaps = new AircraftRigResource.Flap[]{data.flaps[0], data.flaps[0]};
                case 7 -> { data.schema = 1; data.sweeps = null; }
                case 8 -> data.gear = null;
            }
            boolean rejected = false;
            try {
                AircraftRigAnimator.bind(data, model::getBone);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "invalid landing flap resource rejected");
            quaternion(model.getBone("surface").rotation, new Quaternionf(), "failed bind is atomic");
        }
    }

    /** Uses the pinned production Viggen cells, generated hinge basis and independent numeric poses. */
    private static void actualLandingFlapMesh(String fixturePath) throws java.io.IOException {
        var fixture = JsonParser.parseString(java.nio.file.Files.readString(
                java.nio.file.Path.of(fixturePath))).getAsJsonObject();
        var geometry = fixture.getAsJsonObject("geometry");
        var model = new PolyMeshModel(GSON.fromJson(geometry, BedrockModelPOJO.class), geometry);
        var resource = GSON.fromJson(fixture.getAsJsonObject("resource"), DefaultVehicleResource.class);
        var binding = AircraftRigAnimator.bind(resource.getAircraftRig(), model::getBone);
        check(binding.flaps.length == 2 && binding.gear.length == 3, "actual flap/gear census");
        for (var value : fixture.getAsJsonArray("cells")) {
            var cell = value.getAsJsonObject();
            var bone = model.getBone(cell.get("bone").getAsString());
            float[][] points = GSON.fromJson(cell.get("neutralBedrock"), float[][].class);
            var origin = point(bone, new Vector3f());
            for (var poseValue : cell.getAsJsonArray("expectedByGearFraction")) {
                var pose = poseValue.getAsJsonObject();
                float[][] expected = GSON.fromJson(pose.get("points"), float[][].class);
                for (int copy = 0; copy < 2; copy++) {
                    binding.applyFlaps(true, pose.get("fraction").getAsDouble());
                    for (int index = 0; index < points.length; index++) {
                        var local = new Vector3f(points[index]).sub(origin);
                        close(point(bone, local).distance(new Vector3f(expected[index])), 0,
                                "actual source flap vertex follows pinned hinge/reflection");
                    }
                    quaternion(model.getBone("elevon_left").rotation, new Quaternionf(),
                            "landing flap does not rotate existing elevon");
                    quaternion(model.getBone("rudder").rotation, new Quaternionf(),
                            "landing flap does not rotate existing rudder");
                    binding.restore();
                    for (float[] position : points) close(point(bone,
                            new Vector3f(position).sub(origin)).distance(new Vector3f(position)), 0,
                            "actual source flap vertices restore exactly");
                }
            }
        }
    }

    private static PolyMeshModel gearModel() {
        String source = MODEL.replace("{\"name\":\"tail\"", """
                {"name":"gear_nose","parent":"hull","pivot":[2,-3,20]},
                {"name":"gear_left","parent":"hull","pivot":[-8,-3,-4]},
                {"name":"gear_right","parent":"hull","pivot":[8,-3,-4]},
                {"name":"tail"
                """.strip());
        return new PolyMeshModel(GSON.fromJson(source, BedrockModelPOJO.class),
                JsonParser.parseString(source).getAsJsonObject());
    }

    private static AircraftRigResource gearResource() {
        return GSON.fromJson("""
                {"AircraftRig":{"Schema":2,"Frame":"GENERATED_MODEL_PIXELS",
                  "Surfaces":[],"Rotors":[],"Sweeps":[],"Gear":[
                    {"Bone":"gear_nose","Parent":"hull"},
                    {"Bone":"gear_left","Parent":"hull"},
                    {"Bone":"gear_right","Parent":"hull"}]}}
                """, DefaultVehicleResource.class).getAircraftRig();
    }

    private static void gearDoorHingeAndRestore() {
        var model = gearModel();
        var data = gearResource();
        data.gearDoors = new AircraftRigResource.GearDoor[]{GSON.fromJson("""
            {"Bone":"surface","Parent":"hull","Pivot":[7,8,9],"Axis":[0,0,1],"ClosedAngleDegrees":90}
            """, AircraftRigResource.GearDoor.class)};
        var bone = model.getBone("surface");
        var neutral = new Quaternionf(bone.rotation);
        var binding = AircraftRigAnimator.bind(data, model::getBone);
        for (double fraction : new double[]{0, .5, 1, .5, 0}) {
            bone.visible = false;
            binding.applyGear(fraction);
            check(bone.visible, "hinged door remains present while gear retracts");
            quaternion(bone.rotation, new Quaternionf().rotationZ((float) Math.toRadians(-90 * fraction)).mul(neutral),
                    "door follows exact pinned hinge and retraction fraction");
            binding.restore();
            check(!bone.visible, "door restores incoming visibility between shared-model draws");
            quaternion(bone.rotation, neutral, "door restores neutral pose");
        }
        data.gearDoors[0].closedAngleDegrees = Double.NaN;
        boolean rejected = false;
        try { AircraftRigAnimator.bind(data, model::getBone); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "nonfinite door angle rejected before pose mutation");
    }

    private static void gearVisibilityAndRestore() {
        var model = gearModel();
        var binding = AircraftRigAnimator.bind(gearResource(), model::getBone);
        var names = new String[]{"gear_nose", "gear_left", "gear_right"};
        var positions = new Vector3f[names.length];
        var rotations = new Quaternionf[names.length];
        for (int i = 0; i < names.length; i++) {
            var bone = model.getBone(names[i]);
            positions[i] = new Vector3f(bone.x, bone.y, bone.z);
            rotations[i] = new Quaternionf(bone.rotation);
        }
        for (double fraction : new double[]{0, .05, .5, .99999, 1, -1, 1.01,
                Double.NaN, Double.POSITIVE_INFINITY}) {
            // Live and far copies carry the same normalized synchronized fraction.
            for (int copy = 0; copy < 2; copy++) {
                binding.applyGear(fraction);
                for (int i = 0; i < names.length; i++) {
                    var bone = model.getBone(names[i]);
                    check(bone.visible == (fraction != 1), "only exact full retraction hides gear");
                    close(new Vector3f(bone.x, bone.y, bone.z).distance(positions[i]), 0,
                            "visibility does not move original pivots");
                    quaternion(bone.rotation, rotations[i], "no invented gear folding");
                }
                binding.restore();
                for (String name : names) check(model.getBone(name).visible, "shared model restored");
            }
        }
        binding.applyGear(1);
        binding.applyGear(.5);
        check(model.getBone("gear_nose").visible, "repeat apply keeps original incoming state");
        binding.restore();
        var hidden = model.getBone("gear_left");
        hidden.visible = false;
        binding.applyGear(0);
        check(!hidden.visible, "preserve pre-existing hidden topology");
        binding.applyGear(1);
        binding.restore();
        check(!hidden.visible, "restore exact incoming hidden state");
        hidden.visible = true;
        try {
            binding.applyGear(1);
            throw new IllegalStateException("simulated render failure");
        } catch (IllegalStateException expected) {
            check(!model.getBone("gear_nose").visible, "pose active before finally");
        } finally {
            binding.restore();
        }
        check(model.getBone("gear_nose").visible, "render failure restoration");
        var fresh = AircraftRigAnimator.bind(gearResource(), model::getBone);
        fresh.applyGear(0);
        check(model.getBone("gear_nose").visible, "resource rebind remains extended");
        fresh.restore();
    }

    private static void invalidGearAtomicity() {
        for (int failure = 0; failure < 6; failure++) {
            var model = gearModel();
            var data = gearResource();
            switch (failure) {
                case 0 -> data.gear[2].bone = "missing";
                case 1 -> data.gear[1].bone = "gear_nose";
                case 2 -> data.gear[0].parent = "gear_left";
                case 3 -> data.gear = new AircraftRigResource.Gear[17];
                case 4 -> data.gear[2] = null;
                case 5 -> { data.schema = 1; data.sweeps = null; }
            }
            boolean rejected = false;
            try {
                AircraftRigAnimator.bind(data, model::getBone);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            check(rejected, "invalid gear resource rejected atomically");
            check(model.getBone("gear_nose").visible && model.getBone("gear_left").visible,
                    "failed gear bind never mutates earlier valid parts");
        }
        var model = gearModel();
        var data = gearResource();
        data.gear = null;
        var legacy = AircraftRigAnimator.bind(data, model::getBone);
        legacy.applyGear(1);
        check(model.getBone("gear_nose").visible, "absent optional Gear preserves existing rigs");
    }

    private static PolyMeshModel stationModel() {
        String source = """
                {"format_version":"1.12.0","minecraft:geometry":[{
                  "description":{"identifier":"geometry.station","texture_width":16,"texture_height":16,
                    "visible_bounds_width":32,"visible_bounds_height":32,"visible_bounds_offset":[0,0,0]},
                  "bones":[{"name":"hull","pivot":[0,0,0]},
                    {"name":"rearYaw","parent":"hull","pivot":[8,16,96]},
                    {"name":"rearPitch","parent":"rearYaw","pivot":[4.8,17.6,91.2]}]}]}
                """;
        return new PolyMeshModel(GSON.fromJson(source, BedrockModelPOJO.class),
                JsonParser.parseString(source).getAsJsonObject());
    }

    private static DefaultVehicleResource.DefensiveStationResource stationResource() {
        return GSON.fromJson("""
                {"DefensiveStationPresentation":{"Schema":1,"Frame":"GENERATED_MODEL_PIXELS",
                  "Yaw":{"Bone":"rearYaw","Parent":"hull","Pivot":[8,16,96],"Axis":[0,-1,0]},
                  "Pitch":{"Bone":"rearPitch","Parent":"rearYaw","Pivot":[4.8,17.6,91.2],
                    "Axis":[1,0,0]}}}
                """, DefaultVehicleResource.class).getDefensiveStationPresentation();
    }

    private static AircraftRigAnimator.Binding emptyRig(PolyMeshModel model) {
        var data = new AircraftRigResource();
        data.schema = 1;
        data.frame = "GENERATED_MODEL_PIXELS";
        data.surfaces = new AircraftRigResource.Surface[0];
        data.rotors = new AircraftRigResource.Rotor[0];
        return AircraftRigAnimator.bind(data, model::getBone);
    }

    private static void defensiveStationGeometry() {
        var model = stationModel();
        var station = AircraftRigAnimator.bindStation(stationResource(), emptyRig(model), model::getBone);
        var barrel = model.getBone("rearPitch");
        // Neutral mesh already faces rearward. Its local point is reflected into model pixels.
        var localMuzzle = new Vector3f(.8F, .48F, 32F);
        var neutral = point(barrel, new Vector3f(localMuzzle));
        station.apply(true, 0, 0);
        close(point(barrel, new Vector3f(localMuzzle)).distance(neutral), 0, "no baked base-yaw duplication");
        for (double heading : new double[]{-180, -35, 0, 90}) {
            for (double roll : new double[]{-90, 0, 90}) {
                Matrix4d hull = new Matrix4d().translation(12, 5, -18)
                        .rotateY(Math.toRadians(-heading)).rotateX(.3).rotateZ(Math.toRadians(roll));
                for (double yaw : new double[]{-70, -35, 0, 35, 70}) {
                    for (double pitch : new double[]{-40, 0, 60}) {
                        station.apply(true, yaw, pitch);
                        Matrix4d nativeBarrel = new Matrix4d(hull).translate(.5, 1, -6)
                                .rotateY(Math.PI).rotateY(Math.toRadians(yaw))
                                .translate(.2, .1, -.3).rotateX(Math.toRadians(pitch));
                        Vector3d expected = nativeBarrel.transformPosition(new Vector3d(.05, .03, 2));
                        Vector3f posed = point(barrel, new Vector3f(localMuzzle));
                        Vector3d actual = hull.transformPosition(new Vector3d(-posed.x / 16,
                                posed.y / 16, -posed.z / 16));
                        close(actual.distance(expected), 0, "visible/native rear-station muzzle agreement");
                        Vector3f posedForward = point(barrel, new Vector3f(localMuzzle).add(0, 0, 16));
                        Vector3d actualForward = hull.transformPosition(new Vector3d(-posedForward.x / 16,
                                posedForward.y / 16, -posedForward.z / 16)).sub(actual).normalize();
                        Vector3d expectedForward = nativeBarrel.transformDirection(new Vector3d(0, 0, 1));
                        close(actualForward.distance(expectedForward), 0, "visible/native direction agreement");
                    }
                }
            }
        }
        station.restore();
        close(point(barrel, new Vector3f(localMuzzle)).distance(neutral), 0, "station restoration");
    }

    private static void defensiveStationRejectionAndReset() {
        var model = stationModel();
        var binding = emptyRig(model);
        var station = AircraftRigAnimator.bindStation(stationResource(), binding, model::getBone);
        station.apply(true, 35, 20);
        station.apply(false, 35, 20);
        quaternion(model.getBone("rearYaw").rotation, new Quaternionf(), "missing accepted tuple neutral");
        quaternion(model.getBone("rearPitch").rotation, new Quaternionf(), "missing pitch tuple neutral");
        station.apply(true, 35, 20);
        station.apply(true, Double.NaN, 20);
        quaternion(model.getBone("rearYaw").rotation, new Quaternionf(), "nonfinite tuple neutral");
        for (int failure = 0; failure < 5; failure++) {
            var data = stationResource();
            switch (failure) {
                case 0 -> data.schema = 2;
                case 1 -> data.pitch.parent = "hull";
                case 2 -> data.yaw.parent = "rearPitch";
                case 3 -> data.pitch.pivot[0]++;
                case 4 -> data.yaw.axis = new double[]{0, 0, 0};
            }
            boolean rejected = false;
            try { AircraftRigAnimator.bindStation(data, binding, model::getBone); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "malformed station graph rejected");
            quaternion(model.getBone("rearYaw").rotation, new Quaternionf(), "rejection never poses partially");
        }
    }

    private static AircraftRigResource resource() {
        return GSON.fromJson(RESOURCE, DefaultVehicleResource.class).getAircraftRig();
    }

    private static PolyMeshModel modelV2() {
        var root = JsonParser.parseString(MODEL).getAsJsonObject();
        var bones = root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones");
        bones.get(1).getAsJsonObject().addProperty("parent", "wing");
        // Parent follows child in source: ownership is graph-based, not array order.
        bones.add(JsonParser.parseString("""
                {"name":"wing","parent":"hull","pivot":[4,6,7]}
                """));
        return new PolyMeshModel(GSON.fromJson(root, BedrockModelPOJO.class), root);
    }

    private static AircraftRigResource resourceV2() {
        var root = JsonParser.parseString(RESOURCE).getAsJsonObject();
        var rig = root.getAsJsonObject("AircraftRig");
        rig.addProperty("Schema", 2);
        var surface = rig.getAsJsonArray("Surfaces").get(0).getAsJsonObject();
        surface.addProperty("Parent", "wing");
        surface.remove("Channel");
        surface.remove("AngleSign");
        surface.add("ControlWeights", JsonParser.parseString("""
                {"ElevatorUp":1,"RightRoll":-1,"RudderRight":0}
                """));
        rig.add("Sweeps", JsonParser.parseString("""
                [{"Bone":"wing","Parent":"hull","Pivot":[4,6,7],"Axis":[0,1,0],
                  "AngleSign":1,"MaxDeflectionDegrees":60,
                  "Schedule":{"Input":"PRESENTED_SPEED_BLOCKS_PER_TICK",
                    "Interpolation":"LINEAR_CLAMPED","Points":[[0,0],[1,0],[3,1]]}}]
                """));
        return GSON.fromJson(root, DefaultVehicleResource.class).getAircraftRig();
    }

    private static void mixedControlsAndNestedSweep() {
        var model = modelV2();
        var surface = model.getBone("surface");
        var wing = model.getBone("wing");
        close(surface.x, -3, "nested child X pivot");
        close(surface.y, 2, "nested child Y pivot");
        close(surface.z, 2, "nested child Z pivot");
        var data = resourceV2();
        var binding = AircraftRigAnimator.bind(data, model::getBone);
        var phase = new AircraftRigAnimator.Phase(binding);
        Vector3f vertex = new Vector3f(2, 1, 3);
        Vector3f neutral = point(surface, new Vector3f(vertex));
        binding.apply(0, 0, 0, 0, phase);
        close(point(surface, new Vector3f(vertex)).distance(neutral), 0, "neutral geometry exact");
        binding.apply(1, 1, 0, 2, phase);
        quaternion(surface.rotation, new Quaternionf(), "elevator/roll cancellation");
        quaternion(wing.rotation, new Quaternionf().rotateY((float) Math.toRadians(-30)),
                "explicit half-sweep schedule");
        binding.apply(1, -1, 0, 2, phase);
        Quaternionf pitch = new Quaternionf().rotateX((float) Math.toRadians(30));
        Quaternionf sweep = new Quaternionf().rotateY((float) Math.toRadians(-30));
        quaternion(surface.rotation, pitch, "bounded mixed sum saturation");
        Vector3f expected = pitch.transform(new Vector3f(vertex)).add(-3, 2, 2);
        sweep.transform(expected).add(-4, 6, 7);
        close(point(surface, new Vector3f(vertex)).distance(expected), 0,
                "child articulation inherits exact parent pivot once");
        data.surfaces[0].controlWeights.elevatorUp = 0.0;
        data.sweeps[0].schedule.points[2][0] = 200;
        binding.apply(1, 0, 0, 3, phase);
        quaternion(surface.rotation, pitch, "bound weights immutable");
        quaternion(wing.rotation, new Quaternionf().rotateY((float) Math.toRadians(-60)),
                "bound schedule immutable");
        binding.restore();
        close(point(surface, new Vector3f(vertex)).distance(neutral), 0, "whole hierarchy restored");
        var other = new AircraftRigAnimator.Phase(binding);
        binding.apply(0, 0, 0, 0, other);
        quaternion(wing.rotation, new Quaternionf(), "shared model does not retain other vehicle sweep");
    }

    private static Vector3f point(BedrockBone bone, Vector3f value) {
        for (var current = bone; current != null; current = current.parent) {
            current.rotation.transform(value);
            value.add(current.x, current.y, current.z);
        }
        return value;
    }

    private static void sweepScheduleAndFrameIndependence() {
        var model = modelV2();
        var binding = AircraftRigAnimator.bind(resourceV2(), model::getBone);
        var sweep = binding.sweeps[0];
        for (double[] pair : new double[][]{{-1, 0}, {0, 0}, {1, 0}, {1.5, .25},
                {2, .5}, {3, 1}, {1000, 1}, {Double.NaN, 0}, {Double.POSITIVE_INFINITY, 0}}) {
            close(sweep.fraction(pair[0]), pair[1], "bounded schedule interpolation");
        }
        for (int fps : new int[]{30, 60, 120}) {
            var phase = new AircraftRigAnimator.Phase(binding);
            for (int frame = 0; frame <= fps; frame++) {
                double speed = 3.0 * frame / fps;
                binding.apply(0, 0, 0, speed, phase);
                double fraction = Math.max(0, (speed - 1) / 2);
                quaternion(model.getBone("wing").rotation,
                        new Quaternionf().rotateY((float) Math.toRadians(-60 * fraction)),
                        "sweep is FPS-independent and does not accumulate");
            }
        }
        binding.apply(0, 0, 0, 0, new AircraftRigAnimator.Phase(binding));
        quaternion(model.getBone("wing").rotation, new Quaternionf(), "missing/reset input neutral");
        binding.restore();
    }

    private static void invalidV2Atomicity() {
        rejectV2(data -> data.schema = 1);
        rejectV2(data -> data.surfaces[0].channel = "elevatorUp");
        rejectV2(data -> data.surfaces[0].angleSign = 1);
        rejectV2(data -> data.surfaces[0].controlWeights = null);
        rejectV2(data -> data.surfaces[0].controlWeights.elevatorUp = null);
        rejectV2(data -> data.surfaces[0].controlWeights.rightRoll = 1.01);
        rejectV2(data -> data.surfaces[0].controlWeights.elevatorUp = Double.NaN);
        rejectV2(data -> {
            data.surfaces[0].controlWeights.elevatorUp = 0.0;
            data.surfaces[0].controlWeights.rightRoll = 0.0;
        });
        rejectV2(data -> data.sweeps = null);
        rejectV2(data -> data.sweeps = new AircraftRigResource.Sweep[17]);
        rejectV2(data -> data.sweeps[0].parent = "surface");
        rejectV2(data -> data.sweeps[0].pivot[0]++);
        rejectV2(data -> data.sweeps[0].schedule = null);
        rejectV2(data -> data.sweeps[0].schedule.input = "MACH");
        rejectV2(data -> data.sweeps[0].schedule.interpolation = "GUESS");
        rejectV2(data -> data.sweeps[0].schedule.points[1][0] = 3);
        rejectV2(data -> data.sweeps[0].schedule.points[1][1] = 2);
        rejectV2(data -> data.sweeps[0].schedule.points =
                new double[][]{{0, 0}, {1, .75}, {2, .5}, {3, 1}});
        rejectV2(data -> data.sweeps[0].schedule.points[0][1] = .1);
        rejectV2(data -> data.sweeps[0].schedule.points[2][1] = .5);
        rejectV2(data -> data.sweeps[0].schedule.points[1][0] = Double.NaN);
        rejectV2(data -> data.sweeps[0].schedule.points = new double[17][]);
        var model = modelV2();
        model.getBone("wing").parent = model.getBone("surface");
        boolean rejected = false;
        try { AircraftRigAnimator.bind(resourceV2(), model::getBone); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "cyclic model hierarchy rejects without traversal hang");
    }

    private static void rejectV2(java.util.function.Consumer<AircraftRigResource> mutation) {
        var model = modelV2();
        var bone = model.getBone("surface");
        bone.rotation.rotateY(.2F);
        Quaternionf before = new Quaternionf(bone.rotation);
        var data = resourceV2();
        mutation.accept(data);
        boolean rejected = false;
        try { AircraftRigAnimator.bind(data, model::getBone); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid v2 rig rejected");
        quaternion(bone.rotation, before, "invalid v2 binding never partially mutates geometry");
    }

    private static void modelBasisAndNeutral() {
        var model = model();
        var bone = model.getBone("surface");
        close(bone.x, -6, "loader child X");
        close(bone.y, 6, "loader child Y");
        close(bone.z, 6, "loader child Z");
        var binding = AircraftRigAnimator.bind(resource(), model::getBone);
        var phase = new AircraftRigAnimator.Phase(binding);
        binding.apply(0, 0, 0, phase);
        quaternion(bone.rotation, new Quaternionf(), "neutral pose");
        binding.apply(1, 0, 0, phase);
        quaternion(bone.rotation, new Quaternionf().rotateX((float) Math.toRadians(30)),
                "accepted elevator");
        binding.restore();
        quaternion(bone.rotation, new Quaternionf(), "render finally restoration");
        close(bone.x, -6, "no pivot translation");
        close(bone.y, 6, "no pivot translation Y");
        close(bone.z, 6, "no pivot translation Z");
        check(GSON.fromJson("{}", DefaultVehicleResource.class).getAircraftRig() == null,
                "legacy no-rig resource stays absent");
    }

    private static void channelsAndReflection() {
        String[] channels = {"elevatorUp", "rightRoll", "rudderRight"};
        for (int channel = 0; channel < channels.length; channel++) {
            var model = model();
            var data = resource();
            data.surfaces[0].channel = channels[channel];
            data.surfaces[0].angleSign = -1;
            var binding = AircraftRigAnimator.bind(data, model::getBone);
            double[] controls = {0, 0, 0};
            controls[channel] = 2;
            binding.apply(controls[0], controls[1], controls[2], new AircraftRigAnimator.Phase(binding));
            quaternion(model.getBone("surface").rotation,
                    new Quaternionf().rotateX((float) Math.toRadians(-30)), "channel/sign/limit");
            binding.apply(Double.NaN, Double.NaN, Double.NaN, new AircraftRigAnimator.Phase(binding));
            quaternion(model.getBone("surface").rotation, new Quaternionf(), "invalid input neutral");
        }
        for (int i = 1; i <= 100; i++) {
            Vector3f axis = new Vector3f(i + 1, (i % 7) - 3, (i % 13) + 1).normalize();
            Vector3f vertex = new Vector3f(2, -3, 5);
            float radians = (float) Math.toRadians(30);
            // Compiler's Z mirror, followed by loader's X reflection, is diag(-1,1,-1).
            Vector3f expected = new Quaternionf().rotateAxis(radians, axis.x, axis.y, axis.z)
                    .transform(new Vector3f(vertex));
            expected.mul(-1, 1, -1);
            var data = resource();
            data.surfaces[0].axis = new double[]{-axis.x, -axis.y, axis.z};
            var model = model();
            var binding = AircraftRigAnimator.bind(data, model::getBone);
            binding.apply(1, 0, 0, new AircraftRigAnimator.Phase(binding));
            Vector3f actual = model.getBone("surface").rotation.transform(vertex.mul(-1, 1, -1));
            close(actual.distance(expected), 0, "axial/point reflection parity");
            binding.restore();
        }
    }

    private static void rotorsAndClock() {
        var model = model();
        var binding = AircraftRigAnimator.bind(resource(), model::getBone);
        var phase = new AircraftRigAnimator.Phase(binding);
        phase.advance(0, 1);
        phase.advance(1, 1);
        close(phase.degrees[0], 30, "propeller A");
        close(phase.degrees[1], -30, "contra propeller B");
        close(phase.degrees[2], 120, "independent tail rate");
        phase.advance(1, 1);
        close(phase.degrees[0], 30, "same timestamp cannot double-update");
        binding.apply(0, 0, 0, phase);
        quaternion(model.getBone("propA").rotation,
                new Quaternionf().rotateZ((float) Math.toRadians(-30)), "propeller axial conversion");
        quaternion(model.getBone("propB").rotation,
                new Quaternionf().rotateZ((float) Math.toRadians(30)), "contra axial conversion");
        binding.restore();
        for (int fps : new int[]{30, 60, 120}) {
            var sampled = new AircraftRigAnimator.Phase(binding);
            int frames = fps / 2;
            for (int index = 0; index <= frames; index++) {
                double tick = index * 10.0 / frames;
                sampled.advance(tick, 0.2 + tick * 0.08);
            }
            close(sampled.degrees[0], 180, "frame-independent accepted spool integration");
        }
        var secondVehicle = new AircraftRigAnimator.Phase(binding);
        secondVehicle.advance(0, 0);
        secondVehicle.advance(1, 0);
        close(secondVehicle.degrees[0], 0, "shared model independent entity phase");
        phase.advance(0, 1);
        close(phase.degrees[0], 0, "backward clock reset");
        phase.advance(100, 1);
        close(phase.degrees[0], 0, "unload/retrack gap reset");
        phase.advance(Double.NaN, 1);
        close(phase.degrees[0], 0, "invalid clock reset");
    }

    private static void invalidAtomicity() {
        reject(data -> data.schema = 2);
        reject(data -> data.frame = "VEHICLE_LOCAL_BLOCKS");
        reject(data -> data.surfaces = null);
        reject(data -> data.rotors = null);
        reject(data -> data.surfaces[0].channel = "playerCamera");
        reject(data -> data.surfaces[0].angleSign = null);
        reject(data -> data.surfaces[0].angleSign = 0);
        reject(data -> data.surfaces[0].maxDeflectionDegrees = Double.NaN);
        reject(data -> data.surfaces[0].maxDeflectionDegrees = 181.0);
        reject(data -> data.rotors[0].speedChannel = "localThrottleKey");
        reject(data -> data.rotors[0].degreesPerTickAtFullSpeed = 3601.0);
        reject(data -> data.rotors[0].axis = new double[]{0, 0, 0});
        reject(data -> data.rotors[0].axis = new double[]{Double.NaN, 1, 0});
        reject(data -> data.rotors[1].bone = "propA");
        reject(data -> data.rotors[2].bone = "missing");
        reject(data -> data.surfaces[0].parent = "turret");
        reject(data -> data.surfaces[0].pivot[0] += 1);
        reject(data -> data.surfaces = new AircraftRigResource.Surface[33]);
        reject(data -> data.rotors = new AircraftRigResource.Rotor[17]);
        reject(data -> data.surfaces[0] = null);
    }

    private static void reject(java.util.function.Consumer<AircraftRigResource> mutation) {
        var model = model();
        var bone = model.getBone("surface");
        bone.rotation.rotateY(0.25F);
        Quaternionf before = new Quaternionf(bone.rotation);
        var data = resource();
        mutation.accept(data);
        boolean rejected = false;
        try {
            AircraftRigAnimator.bind(data, model::getBone);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "malformed complete rig rejected");
        quaternion(bone.rotation, before, "failed bind never partially mutates model");
    }

    private static void resetAndResourceReplacement() {
        var model = model();
        var oldData = resource();
        var old = AircraftRigAnimator.bind(oldData, model::getBone);
        oldData.surfaces[0].axis[0] = 0;
        oldData.surfaces[0].maxDeflectionDegrees = 89.0;
        old.apply(1, 0, 0, new AircraftRigAnimator.Phase(old));
        quaternion(model.getBone("surface").rotation,
                new Quaternionf().rotateX((float) Math.toRadians(30)), "immutable compiled definition");
        old.restore();
        var freshData = resource();
        freshData.surfaces[0].maxDeflectionDegrees = 12.0;
        var fresh = AircraftRigAnimator.bind(freshData, model::getBone);
        fresh.apply(1, 0, 0, new AircraftRigAnimator.Phase(fresh));
        quaternion(model.getBone("surface").rotation,
                new Quaternionf().rotateX((float) Math.toRadians(12)), "resource replacement new limit");
        fresh.restore();
        quaternion(model.getBone("surface").rotation, new Quaternionf(), "reload neutral restoration");
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
