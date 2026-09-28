package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileCombatDescriptor;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

import java.util.Locale;

/**
 * One line per damage decision of the ground damage model (damage normalization 2026-09-28), so a hit in play can
 * be read back from latest.log: which plate, penetration against line-of-sight armor, hull damage and the HP left,
 * module damage and module health, the ammo-rack roll. Lines start with "[BVP Damage]". Rounds below 20 mm are
 * logged only while diagnostics are on (a machine-gun burst would flood the log); everything is also recorded to
 * the diagnostics stream (category "damage_model") when it runs.
 */
public final class DamageDiagnostics {
    private static final double ALWAYS_LOG_FROM_MM = 20.0D;

    private DamageDiagnostics() {
    }

    static void plate(ArmorTarget target, Entity projectile, ProjectileArmorEffect shot, String plate,
                      double plateMm, double effectiveMm, double penetrationMm, boolean penetrated) {
        emit(target.vehicle(), projectile, "PLATE", String.format(Locale.ROOT,
                "plate=%s plate_mm=%.0f los_mm=%.0f pen_mm=%.0f %s", plate, plateMm, effectiveMm, penetrationMm,
                penetrated ? "PENETRATED" : "STOPPED"),
                "plate", plate, "plate_mm", plateMm, "los_mm", effectiveMm, "pen_mm", penetrationMm,
                "penetrated", penetrated, "tandem", shot.tandemWarhead);
    }

    static void hull(ArmorTarget target, DamageSource source, ProjectileArmorEffect shot, double damage,
                     String cause) {
        ArmoredVehicleEntity vehicle = target.vehicle();
        emit(vehicle, source == null ? null : source.getDirectEntity(), "HULL", String.format(Locale.ROOT,
                "cause=%s damage=%.1f hp=%.1f/%.0f%s", cause, damage, vehicle.getHealth(), vehicle.getMaxHealth(),
                vehicle.isWreck() ? " DESTROYED" : ""),
                "cause", cause, "damage", damage, "health_after", vehicle.getHealth(),
                "max_health", vehicle.getMaxHealth(), "wreck", vehicle.isWreck());
    }

    static void module(ArmorTarget target, Entity projectile, ProjectileArmorEffect shot, String moduleId,
                       String how) {
        ArmoredVehicleEntity vehicle = target.vehicle();
        double damage = shot.moduleDamage(moduleId);
        emit(vehicle, projectile, "MODULE", String.format(Locale.ROOT,
                "module=%s via=%s damage=%.1f module_hp=%.1f%s", moduleId, how, damage,
                vehicle.getModuleHealth(moduleId), vehicle.isModuleDestroyed(moduleId) ? " DESTROYED" : ""),
                "module", moduleId, "via", how, "damage", damage, "module_health", vehicle.getModuleHealth(moduleId),
                "destroyed", vehicle.isModuleDestroyed(moduleId));
    }

    static void ammoRack(ArmorTarget target, Entity projectile, String rack, double chance, double roll,
                         boolean detonates, double rackHealthAfter) {
        emit(target.vehicle(), projectile, "AMMO_RACK", String.format(Locale.ROOT,
                "rack=%s chance=%.3f roll=%.3f %s rack_hp=%.1f", rack, chance, roll,
                detonates ? "DETONATES" : "holds", rackHealthAfter),
                "rack", rack, "chance", chance, "roll", roll, "detonates", detonates, "rack_health", rackHealthAfter);
    }

    /** A line from outside the armor resolver (blast, death explosion). */
    public static void event(ArmoredVehicleEntity vehicle, Entity source, String event, String detail) {
        emit(vehicle, source, event, detail, "detail", detail);
    }

    private static void emit(ArmoredVehicleEntity vehicle, Entity projectile, String event, String detail,
                             Object... fields) {
        ProjectileCombatDescriptor combat = projectile == null ? null : ProjectileProfiles.combatDescriptor(projectile);
        String round = combat == null ? (projectile == null ? "-" : projectile.m_6095_().toString())
                : String.valueOf(combat.getRoundId());
        String kind = combat == null ? "-" : combat.getHullDamageClass().name();
        Double calibre = combat == null ? null : combat.getCaliberMm() != null ? combat.getCaliberMm()
                : combat.getDiameterMm();
        boolean diagnostics = EliteDiagnostics.isEnabled(vehicle.m_9236_());
        if (diagnostics) {
            Object[] all = new Object[fields.length + 6];
            all[0] = "profile"; all[1] = vehicle.getArmorProfileId();
            all[2] = "round"; all[3] = round;
            all[4] = "class"; all[5] = kind;
            System.arraycopy(fields, 0, all, 6, fields.length);
            EliteDiagnostics.record(vehicle, "damage_model", event, all);
        }
        if (diagnostics || calibre == null || calibre >= ALWAYS_LOG_FROM_MM) {
            com.atsuishio.superbwarfare.Mod.LOGGER.info("[BVP Damage] {} vehicle={} round={} class={} cal={} {}",
                    event, vehicle.getArmorProfileId(), round, kind, calibre == null ? "-" : calibre, detail);
        }
    }
}
