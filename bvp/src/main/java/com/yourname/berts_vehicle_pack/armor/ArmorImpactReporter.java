package com.yourname.berts_vehicle_pack.armor;

import com.yourname.berts_vehicle_pack.armor.ArmorProfiles.ArmorBox;
import com.yourname.berts_vehicle_pack.network.BvpNetwork;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Set;

final class ArmorImpactReporter {

    private ArmorImpactReporter() {
    }

    static void reportNoPlateHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                                 ArmorHitResolver.NearBox nearestPlate, ArmorProfiles.Vec localImpact) {
        if (EliteDiagnostics.isEnabled(target.level())) {
            String nearest = nearestPlateDiagnostic(nearestPlate, localImpact);
            logArmorEvent(owner, target, hitVec,
                    "[BVP Armor] Non-Penetration. Shot angle: N/A. Effective armor thickness: N/A. "
                            + "No armor plate matched along the shell ray; strict armor profile blocked the hit. "
                            + nearest);
        }
        sendImpactFeedback(owner, ArmorImpactFeedback.missed());
    }

    static void reportDirectTrackHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                                     ArmorBox trackBox, String side, boolean trackBroken,
                                     boolean newlyDestroyed,
                                     ProjectileArmorEffect shot) {
        String moduleId = "right".equals(side) ? ArmorModuleResolver.RIGHT_TRACK : ArmorModuleResolver.LEFT_TRACK;
        if (EliteDiagnostics.isEnabled(target.level())) {
            String message = trackBroken
                    ? String.format(Locale.ROOT,
                    "[BVP Armor] %s hit disabled %s track for 20.0s. Box: %s.",
                    shot.damageType.displayName,
                    side,
                    trackBox.name)
                    : String.format(Locale.ROOT,
                    "[BVP Armor] %s hit damaged %s track by %.1f HP module damage. Box: %s.",
                    shot.damageType.displayName,
                    side,
                    shot.moduleDamage(),
                    trackBox.name);
            logArmorEvent(owner, target, hitVec, message);
        }
        java.util.List<String> notifications = newlyDestroyed
                ? java.util.List.of(shooterMessage(target, "You've destroyed ", side + " track!"))
                : java.util.List.of();
        sendImpactFeedback(owner, ArmorImpactFeedback.plate(
                ArmorImpactFeedback.Classification.TRACK_HIT,
                Double.NaN, Double.NaN, "Track", target.vehicle().getModuleHealth(moduleId), notifications));
    }

    static void reportDirectModuleHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                                      ArmorBox moduleBox, String moduleId, boolean destroyed,
                                      boolean newlyDestroyed,
                                      ProjectileArmorEffect shot) {
        String normalizedModuleId = ArmorModuleResolver.normalizeModuleId(moduleId);
        if (EliteDiagnostics.isEnabled(target.level())) {
            String status = destroyed ? "destroyed" : "damaged";
            String message = String.format(Locale.ROOT,
                    "[BVP Armor] %s hit %s module %s by %.1f HP module damage. Box: %s.",
                    shot.damageType.displayName,
                    status,
                    moduleId,
                    shot.moduleDamage(moduleId),
                    moduleBox.name);
            logArmorEvent(owner, target, hitVec, message);
        }
        java.util.List<String> notifications = newlyDestroyed
                ? feedbackNotifications(target, null, null, moduleBox, false,
                Set.of(ArmorModuleResolver.normalizeModuleId(moduleId)))
                : java.util.List.of();
        sendImpactFeedback(owner, ArmorImpactFeedback.plate(
                ArmorImpactFeedback.Classification.MODULE_HIT,
                Double.NaN, Double.NaN, moduleBox.name,
                target.vehicle().getModuleHealth(normalizedModuleId), notifications));
    }

    static void reportArmorHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                               ArmorBox plate, double impactCosine, double effectiveArmorMm,
                               double penetrationMm, boolean penetrated, boolean internalHit,
                               ArmorBox engineBox, ArmorBox ammoRack, ArmorBox moduleBox,
                               boolean ammoRackDetonated, ProjectileArmorEffect shot) {
        reportArmorHit(level, owner, target, hitVec, plate, impactCosine, effectiveArmorMm,
                penetrationMm, penetrated, internalHit, engineBox, ammoRack, moduleBox,
                ammoRackDetonated, shot, penetrated
                        ? ArmorImpactFeedback.Classification.PENETRATION
                        : ArmorImpactFeedback.Classification.NON_PENETRATION);
    }

    static void reportArmorHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                               ArmorBox plate, double impactCosine, double effectiveArmorMm,
                               double penetrationMm, boolean penetrated, boolean internalHit,
                               ArmorBox engineBox, ArmorBox ammoRack, ArmorBox moduleBox,
                               boolean ammoRackDetonated, ProjectileArmorEffect shot,
                               ArmorImpactFeedback.Classification classification) {
        reportArmorHit(level, owner, target, hitVec, plate, impactCosine, effectiveArmorMm,
                penetrationMm, penetrated, internalHit, engineBox, ammoRack, moduleBox,
                ammoRackDetonated, shot, classification, Set.of());
    }

    static void reportArmorHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                               ArmorBox plate, double impactCosine, double effectiveArmorMm,
                               double penetrationMm, boolean penetrated, boolean internalHit,
                               ArmorBox engineBox, ArmorBox ammoRack, ArmorBox moduleBox,
                               boolean ammoRackDetonated, ProjectileArmorEffect shot,
                               ArmorImpactFeedback.Classification classification,
                               Set<String> newlyDestroyedModules) {
        if (EliteDiagnostics.isEnabled(target.level())) {
            EliteDiagnostics.record(target.vehicle(), "armor", "resolved_hit",
                    "owner", owner == null ? null : owner.m_20148_(), "profile", target.armorProfileId(),
                    "position", hitVec, "plate", plate.name, "plate_frame", plate.frame,
                    "base_armor_mm", plate.armorMm, "effective_armor_mm", effectiveArmorMm,
                    "angle_degrees", impactAngleDegrees(impactCosine), "penetration_mm", penetrationMm,
                    "penetrated", penetrated, "classification", classification,
                    "damage_type", shot.damageType, "profile_hull_damage", shot.vehicleDamage,
                    "internal_hit", internalHit, "engine_hit", engineBox != null,
                    "ammo_hit", ammoRack != null, "ammo_detonated", ammoRackDetonated,
                    "module", moduleBox == null ? null : moduleBox.name,
                    "module_hp", feedbackModuleHp(target, engineBox, ammoRack, moduleBox),
                    "newly_destroyed_modules", newlyDestroyedModules);
        }
        sendImpactFeedback(owner, ArmorImpactFeedback.plate(
                classification,
                impactAngleDegrees(impactCosine),
                effectiveArmorMm,
                feedbackModuleName(engineBox, ammoRack, moduleBox),
                feedbackModuleHp(target, engineBox, ammoRack, moduleBox),
                feedbackNotifications(target, engineBox, ammoRack, moduleBox, ammoRackDetonated,
                        newlyDestroyedModules)));
    }

    static void reportEraHit(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                             ArmorBox eraBox, ProjectileArmorEffect originalShot,
                             ProjectileArmorEffect reducedShot, double protectionMm) {
        if (EliteDiagnostics.isEnabled(target.level())) {
            String message = String.format(Locale.ROOT,
                    "[BVP Armor] ERA detonated: %s reduced %s penetration by %.0fmm to %.0fmm.",
                    eraBox.name,
                    originalShot.damageType.displayName,
                    protectionMm,
                    reducedShot.penetrationMm);
            logArmorEvent(owner, target, hitVec, message);
        }
        sendHudStatus(level, owner, target, hitVec,
                "Round hit ERA! " + eraBox.eraType.toUpperCase(Locale.ROOT) + " spent.",
                String.format(Locale.ROOT, "Penetration reduced: %.0fmm -> %.0fmm",
                        originalShot.penetrationMm, reducedShot.penetrationMm),
                String.format(Locale.ROOT, "Round hit ERA! Subtracted %.0fmm of penetration!", protectionMm));
    }

    static void logArmorEvent(Entity owner, ArmorTarget target, Vec3 hitVec, String message) {
        if (target != null && EliteDiagnostics.isEnabled(target.level())) {
            EliteDiagnostics.record(target.vehicle(), "armor", "detail",
                    "owner", owner == null ? null : owner.m_20148_(),
                    "profile", target.armorProfileId(), "position", hitVec, "detail", message);
        }
    }

    private static void sendHudStatus(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                                      String line1, String line2) {
        sendHudStatus(level, owner, target, hitVec, line1, line2, "");
    }

    private static void sendHudStatus(Level level, Entity owner, ArmorTarget target, Vec3 hitVec,
                                      String line1, String line2, String line3) {
        // Armor feedback is private shooter UI.  Never fan it out to the target, passengers,
        // nearby observers, or a client-selected recipient.
        if (owner instanceof ServerPlayer player) {
            BvpNetwork.sendArmorEventStatus(player, line1, line2, line3);
        }
    }

    private static void sendImpactFeedback(Entity owner, ArmorImpactFeedback feedback) {
        if (!(owner instanceof ServerPlayer player) || feedback == null) {
            return;
        }
        // Keep line3 available for the existing ERA status contract. Destruction sentences are
        // private chat/system messages, never a third HUD line and never a broadcast.
        BvpNetwork.sendArmorEventStatus(player, feedback.primaryLine(), feedback.moduleLine());
        for (String notification : feedback.shooterNotifications()) {
            if (notification != null && !notification.isBlank()) {
                player.m_213846_(Component.m_237113_(notification));
            }
        }
    }

    private static String nearestPlateDiagnostic(ArmorHitResolver.NearBox nearest,
                                                 ArmorProfiles.Vec localHit) {
        if (nearest == null || localHit == null) {
            return "Nearest armor: none in profile";
        }
        return String.format(Locale.ROOT, "Nearest armor: %s %s dist %.2f local %.2f %.2f %.2f",
                nearest.box().name, nearest.box().frame, nearest.distance(),
                localHit.x, localHit.y, localHit.z);
    }

    private static double impactAngleDegrees(double impactCosine) {
        return Math.toDegrees(Math.acos(Math.max(0.0D, Math.min(1.0D, impactCosine))));
    }

    private static String feedbackModuleName(ArmorBox engineBox, ArmorBox ammoRack, ArmorBox moduleBox) {
        if (ammoRack != null) return "Ammunition";
        if (engineBox != null) return "Engine";
        if (moduleBox == null) return "";
        String moduleId = ArmorModuleResolver.normalizeModuleId(moduleBox.module);
        if (moduleId.isEmpty()) moduleId = ArmorModuleResolver.normalizeModuleId(moduleBox.name);
        if (ArmorModuleResolver.isTrack(moduleId)) {
            return moduleId.contains("right") ? "Right track" : "Left track";
        }
        if (ArmorModuleResolver.ENGINE.equals(moduleId)) return "Engine";
        if (ArmorModuleResolver.isAmmoRack(moduleId)) return "Ammunition";
        if (isWeaponsSystemId(moduleId)) return "Weapon systems";
        return moduleBox.name == null || moduleBox.name.isBlank() ? "Module" : moduleBox.name;
    }

    private static double feedbackModuleHp(ArmorTarget target, ArmorBox engineBox,
                                           ArmorBox ammoRack, ArmorBox moduleBox) {
        String name = feedbackModuleName(engineBox, ammoRack, moduleBox);
        String moduleId = switch (name) {
            case "Engine" -> ArmorModuleResolver.ENGINE;
            case "Ammunition" -> ArmorModuleResolver.ammoRackModuleId(ammoRack);
            case "Weapon systems" -> VehicleModuleHealth.WEAPONS_SYSTEMS_ID;
            default -> moduleBox == null ? "" : ArmorModuleResolver.normalizeModuleId(moduleBox.module);
        };
        return moduleId.isBlank() ? Double.NaN : target.vehicle().getModuleHealth(moduleId);
    }

    private static java.util.List<String> feedbackNotifications(ArmorTarget target, ArmorBox engineBox,
                                                                 ArmorBox ammoRack, ArmorBox moduleBox,
                                                                 boolean ammoRackDetonated,
                                                                 Set<String> newlyDestroyedModules) {
        if (ammoRackDetonated) {
            return java.util.List.of(shooterMessage(target, "BOOM! You've detonated ", "ammunition!"));
        }
        if (engineBox != null && newlyDestroyedModules.contains(ArmorModuleResolver.ENGINE)) {
            return java.util.List.of(shooterMessage(target, "You've disabled ", "engine!"));
        }
        if (moduleBox != null) {
            String moduleId = ArmorModuleResolver.normalizeModuleId(moduleBox.module);
            if (isWeaponsSystemId(moduleId)
                    && newlyDestroyedModules.contains(moduleId)) {
                return java.util.List.of(shooterMessage(target, "You've disabled ", "weapon systems!"));
            }
        }
        return java.util.List.of();
    }

    private static boolean isWeaponsSystemId(String moduleId) {
        return VehicleModuleHealth.WEAPONS_SYSTEMS_ID.equals(moduleId)
                || moduleId.startsWith(VehicleModuleHealth.WEAPONS_SYSTEMS_ID + ":");
    }

    private static String shooterMessage(ArmorTarget target, String prefix, String suffix) {
        Entity driver = target.vehicle().m_20197_().isEmpty()
                ? null : target.vehicle().m_20197_().get(0);
        return driver == null
                ? prefix + "the " + suffix
                : prefix + driver.m_5446_().getString() + "'s " + suffix;
    }

}
