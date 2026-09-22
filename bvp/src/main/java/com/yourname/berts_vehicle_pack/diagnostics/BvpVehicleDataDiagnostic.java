package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData;
import com.atsuishio.superbwarfare.data.vehicle.VehicleData;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Opt-in paired observations of loaded data before and after spawn synchronization. */
public final class BvpVehicleDataDiagnostic {
    // Entity equality uses the numeric ID, shared by the integrated client and server copies.
    private static final Map<VehicleEntity, Integer> CLIENT_STAGES = new WeakHashMap<>();
    private static final Map<VehicleEntity, Integer> SERVER_STAGES = new WeakHashMap<>();

    private BvpVehicleDataDiagnostic() { }

    public static synchronized void record(VehicleEntity vehicle) {
        if (!EliteDiagnostics.isEnabled(vehicle.level())) return;
        int stage = vehicle.tickCount >= 100 ? 2 : vehicle.tickCount >= 40 ? 1 : 0;
        Map<VehicleEntity, Integer> stages = vehicle.level().isClientSide ? CLIENT_STAGES : SERVER_STAGES;
        if (stages.getOrDefault(vehicle, -1) >= stage) return;
        stages.put(vehicle, stage);
        DefaultVehicleData defaults = VehicleData.getDefault(vehicle);
        JsonObject armor = new JsonObject();
        if (vehicle instanceof ArmoredVehicleEntity armored) {
            var profile = ArmorProfiles.get(armored.getArmorProfileId());
            armor.addProperty("profile", profile.id);
            armor.addProperty("plates", profile.plates.size());
            armor.addProperty("engines", profile.engineBoxes.size());
            armor.addProperty("ammo", profile.ammoRacks.size());
            armor.addProperty("tracks", profile.trackBoxes.size());
        }
        EliteDiagnostics.record(vehicle, "vehicle_data", "LOADED_SNAPSHOT",
                "vehicle", vehicle.getUUID(), "registry_id", VehicleData.getRegistryId(vehicle.getType()),
                "entity_class", vehicle.getClass().getName(), "stage", stage,
                "loaded_definition", !defaults.isDefaultData, "override", vehicle.getOverride().toString(),
                "default", projection(defaults).toString(), "computed", projection(vehicle.computed()).toString(),
                "armor", armor.toString());
    }

    private static JsonObject projection(DefaultVehicleData data) {
        JsonObject result = new JsonObject();
        result.addProperty("max_health", data.getMaxHealth());
        JsonObject attachments = new JsonObject();
        for (var entry : new TreeMap<>(data.getAttachments()).entrySet()) {
            JsonObject attachment = new JsonObject();
            attachment.addProperty("parent", entry.getValue().getParent());
            attachment.add("position", vector(entry.getValue().getPosition()));
            attachment.add("direction", vector(entry.getValue().getDirection()));
            attachments.add(entry.getKey(), attachment);
        }
        result.add("attachments", attachments);
        JsonArray seats = new JsonArray();
        for (var seat : data.seats()) {
            JsonObject definition = new JsonObject();
            definition.addProperty("body_attachment", seat.getBodyAttachment());
            definition.add("position", vector(seat.getPosition()));
            JsonArray weapons = new JsonArray();
            seat.weapons().forEach(weapons::add);
            definition.add("weapons", weapons);
            var camera = seat.getCameraPos();
            if (camera != null) {
                JsonObject eye = new JsonObject();
                eye.addProperty("eye", camera.getEyeAttachment());
                eye.addProperty("zoom_eye", camera.getZoomEyeAttachment());
                eye.addProperty("direction", camera.getDirectionAttachment());
                eye.addProperty("zoom_direction", camera.getZoomDirectionAttachment());
                eye.addProperty("mode", String.valueOf(camera.getCameraMode()));
                eye.addProperty("aim_mode", String.valueOf(camera.getAimCameraMode()));
                eye.addProperty("fixed", camera.getUseFixedCameraPos());
                eye.add("position", vector(camera.getPosition()));
                definition.add("camera", eye);
            }
            seats.add(definition);
        }
        result.add("seats", seats);
        return result;
    }

    private static JsonArray vector(Vec3 point) {
        JsonArray result = new JsonArray();
        result.add(point.x);
        result.add(point.y);
        result.add(point.z);
        return result;
    }
}
