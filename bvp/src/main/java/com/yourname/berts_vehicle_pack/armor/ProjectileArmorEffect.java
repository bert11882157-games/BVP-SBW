package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.entity.projectile.CannonShellEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class ProjectileArmorEffect {
    private static final double AMMO_RACK_INSTANT_DETONATION_CHANCE_PER_DAMAGE = 0.08D;

    final ArmorDamageType damageType;
    final double penetrationMm;
    final CannonShellEntity shell;
    final boolean atgm;
    final boolean tandemWarhead;
    final ImpactVisual impactVisual;

    private final double defaultModuleDamage;
    private final Map<String, Double> moduleDamageById;

    final double vehicleDamage;

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          double defaultModuleDamage) {
        this(damageType, penetrationMm, shell, false, defaultModuleDamage, Collections.emptyMap());
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          double defaultModuleDamage, Map<String, Double> moduleDamageById) {
        this(damageType, penetrationMm, shell, false, defaultModuleDamage, moduleDamageById);
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById) {
        this(damageType, penetrationMm, shell, atgm, defaultModuleDamage, moduleDamageById, -1.0D);
    }

    ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                          boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById,
                          double vehicleDamage) {
        this(damageType, penetrationMm, shell, atgm, defaultModuleDamage, moduleDamageById,
                vehicleDamage, false, ImpactVisual.NONE, false);
    }

    private ProjectileArmorEffect(ArmorDamageType damageType, double penetrationMm, CannonShellEntity shell,
                                  boolean atgm, double defaultModuleDamage, Map<String, Double> moduleDamageById,
                                  double vehicleDamage, boolean tandemWarhead, ImpactVisual impactVisual,
                                  boolean overridesNormalized) {
        this.damageType = damageType;
        this.penetrationMm = penetrationMm;
        this.shell = shell;
        this.atgm = atgm;
        this.tandemWarhead = tandemWarhead;
        this.impactVisual = impactVisual;
        this.defaultModuleDamage = defaultModuleDamage;
        this.moduleDamageById = overridesNormalized ? moduleDamageById : normalizeOverrides(moduleDamageById);
        this.vehicleDamage = vehicleDamage;
    }

    double moduleDamage() {
        return defaultModuleDamage;
    }

    ProjectileArmorEffect withDirectDamageScale(double factor) {
        if (factor == 1.0D) return this;
        Map<String, Double> overrides = new HashMap<>();
        moduleDamageById.forEach((id, damage) -> overrides.put(id, damage * factor));
        return new ProjectileArmorEffect(damageType, penetrationMm, shell, atgm,
                defaultModuleDamage * factor, overrides,
                vehicleDamage < 0.0D ? vehicleDamage : vehicleDamage * factor,
                tandemWarhead, impactVisual, true);
    }

    double moduleDamage(String moduleId) {
        String normalized = normalizeModuleId(moduleId);
        Double override = moduleDamageById.get(normalized);
        if (override == null && normalized.startsWith(ArmorModuleResolver.AMMO_RACK + ":")) {
            override = moduleDamageById.get(ArmorModuleResolver.AMMO_RACK);
        }
        return override == null ? defaultModuleDamage : override;
    }

    double ammoRackDamage() {
        return moduleDamage(ArmorModuleResolver.AMMO_RACK);
    }

    double ammoRackInstantDetonationChance() {
        return Math.max(0.0D, Math.min(1.0D,
                ammoRackDamage() * AMMO_RACK_INSTANT_DETONATION_CHANCE_PER_DAMAGE));
    }

    ProjectileArmorEffect withPenetration(double newPenetrationMm) {
        return new ProjectileArmorEffect(this.damageType, Math.max(0.0D, newPenetrationMm),
                this.shell, this.atgm, this.defaultModuleDamage, this.moduleDamageById,
                this.vehicleDamage, this.tandemWarhead, this.impactVisual, true);
    }

    ProjectileArmorEffect withVehicleDamage(double newVehicleDamage) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, newVehicleDamage,
                this.tandemWarhead, this.impactVisual, true);
    }

    /**
     * Replaces the typed armor-effect damage basis without touching penetration, shell behavior,
     * impact presentation, or any other module-specific overrides.  The ammo-rack override is
     * explicit because the normalized small-caliber policies intentionally use a different rack
     * amount from their ordinary-module amount.
     */
    ProjectileArmorEffect withArmorDamage(double newModuleDamage, double newAmmoRackDamage,
                                          double newVehicleDamage) {
        Map<String, Double> overrides = new HashMap<>(this.moduleDamageById);
        overrides.put(ArmorModuleResolver.AMMO_RACK, newAmmoRackDamage);
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                newModuleDamage, overrides, newVehicleDamage,
                this.tandemWarhead, this.impactVisual, true);
    }

    ProjectileArmorEffect withImpactVisual(ImpactVisual newImpactVisual) {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage,
                this.tandemWarhead, newImpactVisual, true);
    }

    ProjectileArmorEffect withTandemWarhead() {
        return new ProjectileArmorEffect(this.damageType, this.penetrationMm, this.shell, this.atgm,
                this.defaultModuleDamage, this.moduleDamageById, this.vehicleDamage, true, this.impactVisual, true);
    }

    Map<String, Double> moduleDamageOverrides() {
        return moduleDamageById;
    }

    private static Map<String, Double> normalizeOverrides(Map<String, Double> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : overrides.entrySet()) {
            if (entry.getValue() != null) {
                normalized.put(normalizeModuleId(entry.getKey()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static String normalizeModuleId(String moduleId) {
        return moduleId == null
                ? ""
                : moduleId.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    enum ImpactVisual {
        NONE,
        BULLET,
        HMG,
        AUTOCANNON_AP,
        AUTOCANNON_HE,
        APFSDS,
        HEAT_FS,
        HE,
        ATGM
    }
}
