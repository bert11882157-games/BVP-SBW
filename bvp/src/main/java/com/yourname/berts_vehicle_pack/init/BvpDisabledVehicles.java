package com.yourname.berts_vehicle_pack.init;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Vehicles hidden from every player-facing list (creative tabs, SBW lucky-container loot) while their entity types,
 * assets and data stay registered, so saved worlds and spawned entities still load.  One list:
 * {@code data/berts_vehicle_pack/disabled_vehicles.json} (2026-10-23, bert).  Empty its array to restore everything.
 */
public final class BvpDisabledVehicles {
    private static final String PATH = "/data/berts_vehicle_pack/disabled_vehicles.json";
    private static final Set<String> IDS = load();

    private BvpDisabledVehicles() {
    }

    /** Bare vehicle ids (no namespace), in file order. */
    public static Set<String> ids() {
        return IDS;
    }

    public static boolean isDisabled(String vehicleId) {
        return IDS.contains(vehicleId);
    }

    /** Same ids as {@code berts_vehicle_pack:<id>}, the form SBW container lists use. */
    public static Set<String> namespacedIds() {
        Set<String> out = new LinkedHashSet<>();
        for (String id : IDS) {
            out.add("berts_vehicle_pack:" + id);
        }
        return out;
    }

    /** The visible subset of {@code vehicleIds}, order kept. */
    public static List<String> visible(List<String> vehicleIds) {
        List<String> out = new ArrayList<>(vehicleIds.size());
        for (String id : vehicleIds) {
            if (!IDS.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static Set<String> load() {
        try (InputStream stream = BvpDisabledVehicles.class.getResourceAsStream(PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Missing BVP disabled-vehicle list: " + PATH);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalStateException("Unsupported BVP disabled-vehicle list version");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement element : root.getAsJsonArray("disabled")) {
                String id = element.getAsString();
                if (!id.matches("[a-z0-9_]+") || !ids.add(id)) {
                    throw new IllegalStateException("Invalid/duplicate disabled vehicle: " + id);
                }
            }
            return java.util.Collections.unmodifiableSet(ids);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load BVP disabled-vehicle list", exception);
        }
    }
}
