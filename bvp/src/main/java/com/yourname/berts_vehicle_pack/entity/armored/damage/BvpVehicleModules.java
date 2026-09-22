package com.yourname.berts_vehicle_pack.entity.armored.damage;

import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleAdapter;
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleDefinition;
import com.atsuishio.superbwarfare.api.vehicle.module.VehicleModuleProviders;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import com.yourname.berts_vehicle_pack.armor.VehicleModuleHealth;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** BVP-authored IDs and health values consumed by the generic custom-SBW module state. */
public final class BvpVehicleModules {
    public static final ResourceLocation LEFT_TRACK = id("lefttrack");
    public static final ResourceLocation RIGHT_TRACK = id("righttrack");
    public static final ResourceLocation MAIN_ENGINE = id("mainengine");
    public static final ResourceLocation SUB_ENGINE = id("subengine");

    private static final ResourceLocation PROVIDER_ID = id("module_definitions");
    private static final Map<String, ResourceLocation> NORMALIZED_IDS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, VehicleModuleDefinition> DEFINITIONS = new ConcurrentHashMap<>();

    private BvpVehicleModules() {
    }

    public static void register() {
        VehicleModuleProviders.registerDefinitionProvider(PROVIDER_ID, (vehicle, moduleId) -> {
            if (!(vehicle instanceof ArmoredVehicleEntity)
                    || !BertsVehiclePack.MODID.equals(moduleId.m_135827_())) {
                return null;
            }
            return DEFINITIONS.computeIfAbsent(moduleId, BvpVehicleModules::definition);
        });
    }

    public static ResourceLocation idForNormalized(String normalizedId) {
        String normalized = (normalizedId == null ? "" : normalizedId).replace(':', '/');
        return NORMALIZED_IDS.computeIfAbsent(normalized, BvpVehicleModules::id);
    }

    private static VehicleModuleDefinition definition(ResourceLocation moduleId) {
        String path = moduleId.m_135815_();
        return switch (path) {
            case "lefttrack" -> definition(moduleId, VehicleModuleHealth.TRACK_HP,
                    VehicleModuleAdapter.LEGACY_RUNNING_GEAR_LEFT);
            case "righttrack" -> definition(moduleId, VehicleModuleHealth.TRACK_HP,
                    VehicleModuleAdapter.LEGACY_RUNNING_GEAR_RIGHT);
            case "mainengine" -> definition(moduleId, VehicleModuleHealth.ENGINE_HP,
                    VehicleModuleAdapter.LEGACY_ENGINE_MAIN);
            case "subengine" -> definition(moduleId, VehicleModuleHealth.ENGINE_HP,
                    VehicleModuleAdapter.LEGACY_ENGINE_SUB);
            default -> definition(moduleId, logicalMaxHealth(path), VehicleModuleAdapter.GENERIC);
        };
    }

    private static double logicalMaxHealth(String path) {
        if (path.equals("ammorack") || path.startsWith("ammorack/")) {
            return VehicleModuleHealth.AMMO_RACK_HP;
        }
        if (path.equals(VehicleModuleHealth.WEAPONS_SYSTEMS_ID)
                || path.startsWith(VehicleModuleHealth.WEAPONS_SYSTEMS_ID + "/")) {
            return VehicleModuleHealth.WEAPONS_SYSTEMS_HP;
        }
        return VehicleModuleHealth.GENERIC_MODULE_HP;
    }

    private static VehicleModuleDefinition definition(ResourceLocation id, double maxHealth,
                                                       VehicleModuleAdapter adapter) {
        return new VehicleModuleDefinition(id, (float) maxHealth, adapter);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(BertsVehiclePack.MODID, path);
    }
}
