package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.resource.pojo.BedrockModelPOJO;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Binds every generated aircraft rig against its generated geometry with the production binder, so a bad pivot,
 * parent chain or steering entry fails here instead of silently dropping the whole rig in game. Also pins the
 * rudder / nose-wheel sense to the flight model's yaw, and binds each DefensiveStationPresentation with
 * bindStation (a non-null station, no IllegalArgumentException).
 * Usage: GeneratedAircraftRigBindTest <assets/berts_vehicle_pack dir>
 */
public final class GeneratedAircraftRigBindTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws java.io.IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path assets = Path.of(args[0]);
        List<String> failures = new ArrayList<>();
        int bound = 0, steered = 0, stations = 0;
        List<String> stationIds = new ArrayList<>();
        try (var files = Files.list(assets.resolve("sbw/vehicles"))) {
            for (Path vehicle : files.sorted().toList()) {
                String id = vehicle.getFileName().toString().replace(".json", "");
                // only the rig subtree: the full resource needs the game's registered type adapters
                var root = JsonParser.parseString(Files.readString(vehicle)).getAsJsonObject();
                if (!root.has("AircraftRig")) continue;
                var wrapper = new com.google.gson.JsonObject();
                wrapper.add("AircraftRig", root.get("AircraftRig"));
                var rig = GSON.fromJson(wrapper, DefaultVehicleResource.class).getAircraftRig();
                // Positive rudder yaws the nose toward geo +x (Minecraft yaw falls): a rudder's trailing edge and a
                // steered wheel's front must both move toward geo +x, i.e. turn positively about geo -y.
                if (rig.surfaces != null) for (var surface : rig.surfaces) {
                    var weights = surface.controlWeights;
                    if (weights != null && weights.rudderRight != null && weights.rudderRight != 0
                            && surface.axis != null && Math.abs(surface.axis[1]) > 0.2
                            && weights.rudderRight * surface.axis[1] < 0) {
                        failures.add(id + ": rudder " + surface.bone + " deflects against the yaw it commands");
                    }
                }
                if (rig.noseSteering != null) for (var steer : rig.noseSteering) {
                    if (steer.axis == null || steer.axis[0] != 0 || steer.axis[1] != -1 || steer.axis[2] != 0) {
                        failures.add(id + ": nose steering " + steer.bone + " axis must be [0,-1,0]");
                    }
                }
                Path geoPath = assets.resolve("custom_geo/" + id + ".geo.json");
                if (!Files.exists(geoPath)) {
                    failures.add(id + ": no custom_geo");
                    continue;
                }
                var geometry = JsonParser.parseString(Files.readString(geoPath)).getAsJsonObject();
                var model = new PolyMeshModel(GSON.fromJson(geometry, BedrockModelPOJO.class), geometry);
                try {
                    var binding = AircraftRigAnimator.bind(rig, model::getBone);
                    bound++;
                    if (binding.steering.length != 0) {
                        steered++;
                        binding.applySteering(true, 0, 1, 0);
                        binding.restore();
                        for (var steer : binding.steering) {
                            if (!new Quaternionf().equals(steer.bone.rotation, 1.0E-6F)
                                    && !steer.bone.rotation.equals(steer.neutral, 1.0E-6F)) {
                                failures.add(id + ": steering did not restore");
                            }
                        }
                    }
                    // The renderer binds the defensive station right after the rig; a pivot that differs from the
                    // geo's makes bindStation throw, which drops the station ("Aircraft rig skipped ... pivot
                    // mismatch" in the log) and leaves its bones frozen.
                    if (root.has("DefensiveStationPresentation")) {
                        var stationWrapper = new com.google.gson.JsonObject();
                        stationWrapper.add("DefensiveStationPresentation", root.get("DefensiveStationPresentation"));
                        var presentation = GSON.fromJson(stationWrapper, DefaultVehicleResource.class)
                                .getDefensiveStationPresentation();
                        var station = AircraftRigAnimator.bindStation(presentation, binding, model::getBone);
                        if (station == null) failures.add(id + ": station binding is null");
                        else {
                            stations++;
                            stationIds.add(id);
                            station.apply(true, 25, 15);
                            station.restore();
                        }
                    }
                } catch (IllegalArgumentException invalid) {
                    failures.add(id + ": " + invalid.getMessage());
                }
            }
        }
        // the Cheyenne's belly station must be bound, not merely absent from the scan (the 23230ae7 pivot regression)
        if (!stationIds.contains("ah_56a")) failures.add("ah_56a: DefensiveStationPresentation not bound");
        failures.forEach(failure -> System.out.println("FAIL " + failure));
        System.out.println((failures.isEmpty() ? "PASS" : "FAIL") + " generated aircraft rigs: " + bound
                + " bound, " + steered + " with nose steering, " + stations + " with a bound station, "
                + failures.size() + " failures");
        if (!failures.isEmpty()) System.exit(1);
    }
}
