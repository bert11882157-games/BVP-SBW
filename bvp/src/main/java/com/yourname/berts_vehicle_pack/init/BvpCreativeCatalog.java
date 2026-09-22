package com.yourname.berts_vehicle_pack.init;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Immutable packaged projection of the vehicle pack's creative category authoring. */
final class BvpCreativeCatalog {
    private static final Map<String, Category> CATEGORIES = load();

    record Category(String id, String iconVehicleId, List<String> vehicleIds) {
    }

    private BvpCreativeCatalog() {
    }

    static Category category(String id) {
        Category category = CATEGORIES.get(id);
        if (category == null) {
            throw new IllegalStateException("Missing BVP creative category: " + id);
        }
        return category;
    }

    static EntityType<?> entity(String id) {
        ResourceLocation key = new ResourceLocation(BertsVehiclePack.MODID, id);
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(key);
        if (type == null || !ForgeRegistries.ENTITY_TYPES.containsKey(key)) {
            throw new IllegalStateException("Unregistered BVP creative vehicle: " + id);
        }
        return type;
    }

    private static Map<String, Category> load() {
        String path = "/data/" + BertsVehiclePack.MODID + "/creative_tabs.json";
        try (InputStream stream = BvpCreativeCatalog.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing BVP creative catalog: " + path);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                    stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) {
                throw new IllegalStateException("Unsupported BVP creative catalog version");
            }
            Map<String, Category> categories = new LinkedHashMap<>();
            Set<String> vehicles = new HashSet<>();
            JsonArray entries = root.getAsJsonArray("categories");
            for (JsonElement entry : entries) {
                JsonObject row = entry.getAsJsonObject();
                String id = row.get("id").getAsString();
                String icon = row.get("iconVehicleId").getAsString();
                List<String> ids = new ArrayList<>();
                for (JsonElement vehicle : row.getAsJsonArray("vehicleIds")) {
                    String vehicleId = vehicle.getAsString();
                    if (!vehicleId.matches("[a-z0-9_]+") || !vehicles.add(vehicleId)) {
                        throw new IllegalStateException("Invalid/duplicate creative vehicle: " + vehicleId);
                    }
                    ids.add(vehicleId);
                }
                if (!ids.contains(icon) || categories.putIfAbsent(id,
                        new Category(id, icon, List.copyOf(ids))) != null) {
                    throw new IllegalStateException("Invalid/duplicate creative category: " + id);
                }
            }
            if (!categories.keySet().equals(Set.of("tanks", "ifv_apc", "aircraft", "misc"))) {
                throw new IllegalStateException("BVP creative category census does not match its tabs");
            }
            return Map.copyOf(categories);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to load BVP creative catalog", exception);
        }
    }
}
