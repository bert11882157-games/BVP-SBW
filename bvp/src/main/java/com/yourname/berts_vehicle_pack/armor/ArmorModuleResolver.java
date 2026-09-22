package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorHitResolver.ShotTrace;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorHit;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorProfile;
import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.Vec;

import java.util.Locale;

final class ArmorModuleResolver {
    static final String LEFT_TRACK = "lefttrack";
    static final String RIGHT_TRACK = "righttrack";
    static final String TRACK = "track";
    static final String ENGINE = "engine";
    static final String AMMO_RACK = "ammorack";
    private ArmorModuleResolver() {
    }

    static ModuleHit findDirectTrackHit(ArmorTarget target, ArmorProfile profile, ShotTrace trace) {
        ArmorHit hit = ArmorHitResolver.findBestBox(target, profile.trackBoxes, trace,
                ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
        if (hit == null) {
            return null;
        }
        String side = trackSide(hit.plate, hit.hullImpact);
        return new ModuleHit(hit, "right".equals(side) ? RIGHT_TRACK : LEFT_TRACK, side);
    }

    static ModuleHit findDirectModuleHit(ArmorTarget target, ArmorProfile profile, ShotTrace trace) {
        ArmorHit hit = ArmorHitResolver.findBestBox(target, profile.moduleBoxes, trace,
                ArmorHitResolver.ARMOR_RAY_DISTANCE_BLOCKS, profile.impactTolerance);
        if (hit == null) {
            return null;
        }
        String moduleId = moduleIdForBox(hit.plate);
        if (moduleId.isEmpty()) {
            return null;
        }
        return new ModuleHit(hit, moduleId, trackSideIfTrack(moduleId, hit.plate, hit.hullImpact));
    }

    static InternalModuleHits findInternalHits(ArmorTarget target, ArmorProfile profile, ArmorHit armorHit,
                                               ShotTrace trace) {
        Vec rayOrigin = armorHit == null ? trace.hullImpactFallback : armorHit.hullImpact;
        boolean sensitiveInternal = ArmorHitResolver.findFirstBoxOnRay(target, profile.sensitiveInternals,
                rayOrigin, trace.hullShotDirection, profile.internalRayLength, profile.impactTolerance) != null;
        ArmorHit engineHit = ArmorHitResolver.findFirstBoxOnRay(target, profile.engineBoxes,
                rayOrigin, trace.hullShotDirection, profile.internalRayLength, profile.impactTolerance);
        ArmorHit ammoRackHit = ArmorHitResolver.findFirstBoxOnRay(target, profile.ammoRacks,
                rayOrigin, trace.hullShotDirection, profile.internalRayLength, profile.impactTolerance);
        ArmorHit moduleHit = ArmorHitResolver.findFirstBoxOnRay(target, profile.moduleBoxes,
                rayOrigin, trace.hullShotDirection, profile.internalRayLength, profile.impactTolerance);
        String moduleId = moduleHit == null ? "" : moduleIdForBox(moduleHit.plate);
        return new InternalModuleHits(sensitiveInternal, engineHit, ammoRackHit, moduleHit, moduleId);
    }

    static String normalizeModuleId(String moduleId) {
        String normalized = moduleId == null
                ? ""
                : moduleId.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (normalized) {
            case "left", "ltrack", "trackleft", "leftwheel", "leftwheels" -> LEFT_TRACK;
            case "right", "rtrack", "trackright", "rightwheel", "rightwheels" -> RIGHT_TRACK;
            case "tracks" -> TRACK;
            case "engines", "mainengine", "subengine", "motor", "motors", "powerpack" -> ENGINE;
            case "ammo", "ammoracks", "ammunitionrack" -> AMMO_RACK;
            case "weaponssystem", "weaponssystems", "weaponsystem" -> VehicleModuleHealth.WEAPONS_SYSTEMS_ID;
            default -> normalized;
        };
    }

    static boolean isTrack(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        return TRACK.equals(normalized) || LEFT_TRACK.equals(normalized) || RIGHT_TRACK.equals(normalized);
    }

    static boolean isAmmoRack(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        return AMMO_RACK.equals(normalized) || normalized.startsWith(AMMO_RACK + ":");
    }

    static String ammoRackModuleId(ArmorBox box) {
        String boxId = box == null ? "unknown" : moduleIdForBox(box);
        if (boxId.isEmpty() || AMMO_RACK.equals(boxId)) {
            boxId = box == null ? "unknown" : box.name;
        }
        return AMMO_RACK + ":" + boxId;
    }

    private static String moduleIdForBox(ArmorBox box) {
        String explicit = normalizeModuleId(box.module);
        String moduleId = explicit.isEmpty() ? normalizeModuleId(box.name) : explicit;
        if (moduleId.isEmpty() || box.unified || moduleId.contains(":")) {
            return moduleId;
        }
        String boxId = normalizeModuleId(box.name);
        return boxId.isEmpty() ? moduleId : moduleId + ":" + boxId;
    }

    private static String trackSideIfTrack(String moduleId, ArmorBox box, Vec localImpact) {
        return isTrack(moduleId) ? trackSide(box, localImpact) : "";
    }

    private static String trackSide(ArmorBox trackBox, Vec localImpact) {
        String name = trackBox.name.toLowerCase(Locale.ROOT);
        if (name.contains("right") || name.endsWith("_r") || name.contains("_r_")) {
            return "right";
        }
        if (name.contains("left") || name.endsWith("_l") || name.contains("_l_")) {
            return "left";
        }
        return localImpact.x < 0.0D ? "right" : "left";
    }

    static final class ModuleHit {
        final ArmorHit hit;
        final String moduleId;
        final String trackSide;

        ModuleHit(ArmorHit hit, String moduleId, String trackSide) {
            this.hit = hit;
            this.moduleId = moduleId;
            this.trackSide = trackSide;
        }
    }

    static final class InternalModuleHits {
        final boolean sensitiveInternal;
        final ArmorHit engineHit;
        final ArmorHit ammoRackHit;
        final ArmorHit moduleHit;
        final String moduleId;

        InternalModuleHits(boolean sensitiveInternal, ArmorHit engineHit, ArmorHit ammoRackHit,
                           ArmorHit moduleHit, String moduleId) {
            this.sensitiveInternal = sensitiveInternal;
            this.engineHit = engineHit;
            this.ammoRackHit = ammoRackHit;
            this.moduleHit = moduleHit;
            this.moduleId = moduleId;
        }

        ArmorBox engineBox() {
            return engineHit == null ? null : engineHit.plate;
        }

        ArmorBox ammoRackBox() {
            return ammoRackHit == null ? null : ammoRackHit.plate;
        }

        boolean criticalHit() {
            return sensitiveInternal || ammoRackHit != null;
        }
    }
}
